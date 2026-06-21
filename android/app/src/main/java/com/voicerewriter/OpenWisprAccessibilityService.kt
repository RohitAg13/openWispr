package com.voicerewriter

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.widget.Toast
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Makes "insert the rewrite into the field I was typing in" possible. An overlay
 * cannot write into other apps — only an IME, the owning app, or an accessibility
 * service can.
 *
 * Insertion is event-driven: RewriteActivity hands us the text and finishes; once
 * the *host* app regains window focus (we ignore our own windows), we paste the
 * clipboard into its focused editable field. Timed retries back this up in case
 * no focus event fires. This avoids the trap of inserting while our own activity
 * is still the active window.
 */
class OpenWisprAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "RewriterA11y"

        @Volatile
        private var instance: OpenWisprAccessibilityService? = null

        /**
         * Package of the last non-self app to take window focus. The dictation
         * pipeline reads this to decide whether the target is a code/terminal field
         * (where spoken "dot"/"slash"/"dash" should pass through as words).
         */
        @Volatile
        var lastHostPackage: String? = null
            private set

        /** True when the user has enabled the service in Accessibility settings. */
        val isEnabled: Boolean get() = instance != null

        /**
         * Stage [text] on the clipboard and insert it into the host app's focused
         * field as soon as that app is back in front. Returns true if the service
         * is running (so the caller knows auto-insert will be attempted).
         * Call from the main thread.
         */
        fun enqueueInsert(text: String): Boolean {
            val svc = instance ?: return false
            svc.startInsert(text)
            return true
        }

        /** Re-check focus now (e.g. when the bubble (re)starts) so it shows if a field is already focused. */
        fun reevaluate() {
            val svc = instance ?: return
            svc.main.post { svc.evaluateFieldFocus() }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var pendingText: String? = null
    private val retryDelays = longArrayOf(250, 500, 900, 1400, 2000)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "service connected")
        // Now that focus detection is available, let the bubble switch to its
        // "only show on text fields" behavior (it starts always-visible without us).
        BubbleService.instance?.refreshGating()
        main.post { evaluateFieldFocus() }
    }

    /** Debounced re-check of whether a host text field is focused (drives the bubble). */
    private val fieldCheck = Runnable { evaluateFieldFocus() }

    /**
     * Tell the bubble whether the foreground app currently has a focused editable
     * field. Skips our own windows so the recording sheet doesn't flap the bubble.
     */
    private fun evaluateFieldFocus() {
        // Scan all interactive windows, not just rootInActiveWindow — across an app
        // switch the "active window" can be null/transient, which left the bubble
        // stuck. The focused editable lives in whichever window holds input focus.
        val wins = try { windows } catch (_: Exception) { null }
        if (wins.isNullOrEmpty()) {
            val root = rootInActiveWindow ?: return
            if (root.packageName == packageName) return
            val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            val editable = f != null && f.isEditable
            @Suppress("DEPRECATION") f?.recycle()
            Log.d(TAG, "fieldFocus(fallback) editable=$editable")
            BubbleService.instance?.setFieldFocused(editable)
            return
        }
        var editable = false
        var ourModalActive = false
        for (w in wins) {
            val root = w.root ?: continue
            if (root.packageName == packageName) {
                // Our recording/transform sheet (an activity) — don't flap the bubble.
                // The bubble's own overlay window is harmless; only the modal counts.
                if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) ourModalActive = true
                continue
            }
            val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (f != null) {
                if (f.isEditable) editable = true
                @Suppress("DEPRECATION") f.recycle()
            }
            if (editable) break
        }
        if (ourModalActive) return // leave the bubble as-is while our sheet is up
        Log.d(TAG, "fieldFocus editable=$editable host=$lastHostPackage")
        BubbleService.instance?.setFieldFocused(editable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // Track the foreground host app (cheap: window changes are infrequent) so the
        // dictation pipeline can adapt normalization to code/terminal fields.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (pkg != null && pkg != packageName) lastHostPackage = pkg
        }
        // Drive the field-gated bubble: re-check focus on any event that can change it
        // (coalesced — content-changed can fire in bursts).
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                main.removeCallbacks(fieldCheck)
                main.postDelayed(fieldCheck, 120)
            }
        }
        if (pendingText == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val pkg = event.packageName
                if (pkg != null && pkg != packageName) attemptInsert()
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(fieldCheck)
        if (instance === this) instance = null
        // Service gone — focus detection is impossible, so let the bubble show always.
        BubbleService.instance?.refreshGating()
    }

    // ---------------- insertion ----------------

    private fun startInsert(text: String) {
        setClipboard(text)
        pendingText = text
        main.removeCallbacksAndMessages(null)
        // Backstop retries in case the focus event doesn't arrive.
        for (delay in retryDelays) main.postDelayed({ attemptInsert() }, delay)
        // Give up after the last retry: leave it on the clipboard.
        main.postDelayed({
            if (pendingText != null) {
                pendingText = null
                toast("No text field focused — copied instead")
            }
        }, retryDelays.last() + 300)
    }

    /** Try once to paste into the host app's focused editable field. */
    private fun attemptInsert() {
        if (pendingText == null) return
        val node = findHostFocusedEditable() ?: return
        val ok = try {
            // Paste honors cursor position and replaces any active selection.
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE) || run {
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        pendingText,
                    )
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
        } catch (e: Exception) {
            Log.e(TAG, "insert action failed", e); false
        } finally {
            @Suppress("DEPRECATION") node.recycle()
        }
        if (ok) {
            Log.i(TAG, "inserted into host field")
            pendingText = null
            main.removeCallbacksAndMessages(null)
            vibrateTick()
            toast("Inserted")
        }
    }

    /** Focused editable node in the active window, only if it's NOT our own app. */
    private fun findHostFocusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName == packageName) return null // our sheet is still up
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) return focused
        @Suppress("DEPRECATION") focused?.recycle()
        return null
    }

    private fun setClipboard(text: String) {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("rewrite", text))
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** Light confirmation buzz when text lands in the field. */
    private fun vibrateTick() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        } ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") vibrator.vibrate(20)
            }
        } catch (_: Exception) {}
    }
}
