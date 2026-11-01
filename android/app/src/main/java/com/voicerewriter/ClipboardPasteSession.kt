package com.voicerewriter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

internal data class ClipboardPasteCapture(val session: ClipboardPasteSession?, val reason: String)

/** Temporarily use the clipboard for an accessibility paste, then restore it. */
internal class ClipboardPasteSession(
    private val stage: (String) -> Unit,
    private val restore: () -> Unit,
    private val onRestoreFailure: (Exception) -> Unit,
    private val afterSuccessfulPaste: (() -> Unit) -> Unit = { it() },
) {
    fun paste(text: String, performPaste: () -> Boolean): Boolean {
        stage(text)
        var pasted = false
        return try {
            performPaste().also { pasted = it }
        } finally {
            // A rejected paste may be followed by a successful direct-insert retry.
            // Restore on every attempt so that retry cannot leave our text behind.
            val restoreSafely: () -> Unit = {
                try {
                    restore()
                } catch (e: Exception) {
                    // A successful paste must not be retried (duplicating text)
                    // merely because restoring the clipboard failed.
                    onRestoreFailure(e)
                }
            }
            // WebView editors may consume the clipboard after accepting ACTION_PASTE.
            // Give them time to read it; rejected actions can be restored immediately.
            if (pasted) afterSuccessfulPaste(restoreSafely) else restoreSafely()
        }
    }

    companion object {
        /**
         * Called while RewriteActivity still has focus, before handing control back
         * to the host. Android 10+ prevents background services reading the clipboard.
         * If it cannot be preserved, skip automatic clipboard paste; direct insertion
         * and the eventual copy-on-failure fallback remain available.
         */
        fun capture(context: Context, hasWindowFocus: Boolean): ClipboardPasteCapture {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasWindowFocus) {
                return ClipboardPasteCapture(null, "no_window_focus")
            }
            return try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val original = clipboard.primaryClip
                if (original == null && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    return ClipboardPasteCapture(null, "cannot_restore_empty_clipboard")
                }
                // Replacing a URI/intent clip can revoke the temporary permissions
                // needed to restore it. Do not disturb such clips for auto-paste.
                if (original != null && (0 until original.itemCount).any {
                        val item = original.getItemAt(it)
                        item.uri != null || item.intent != null
                    }) {
                    return ClipboardPasteCapture(null, "uri_or_intent_clip")
                }
                ClipboardPasteCapture(ClipboardPasteSession(
                    stage = { text -> clipboard.setPrimaryClip(ClipData.newPlainText("rewrite", text)) },
                    restore = {
                        if (original != null) clipboard.setPrimaryClip(original)
                        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) clipboard.clearPrimaryClip()
                    },
                    onRestoreFailure = { Log.e("OpenWisprA11y", "clipboard restore failed", it) },
                    // A separate handler survives the accessibility service cancelling
                    // its insertion retries after success.
                    afterSuccessfulPaste = { restore ->
                        Handler(Looper.getMainLooper()).postDelayed({ restore() }, 300)
                    },
                ), if (original == null) "empty_clipboard_saved" else "clipboard_saved")
            } catch (e: Exception) {
                Log.w("OpenWisprA11y", "clipboard snapshot unavailable; skipping auto-paste", e)
                ClipboardPasteCapture(null, "snapshot_exception_${e.javaClass.simpleName}")
            }
        }
    }
}
