package com.voicerewriter

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.arm.aichat.AiChat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.math.abs

/**
 * Foreground service that paints a draggable bubble. Gestures:
 *  - single tap  → start dictation (or stop the current recording)
 *  - long press  → start a clipboard-rewrite recording
 *  - drag        → reposition
 *
 * Recording itself runs in RewriteActivity (a visible activity legitimately holds
 * the mic on Android 14; a background overlay cannot start a mic service). While
 * recording, the activity calls back here to swap the bubble to a live waveform.
 */
class BubbleService : Service() {

    companion object {
        @Volatile
        var isRunning = false

        /** The running bubble, so RewriteActivity can drive its visual state. */
        @Volatile
        var instance: BubbleService? = null

        /**
         * Set by the active recording activity to its stop() handler; null when not
         * recording. The bubble invokes it (on the main thread) to stop & process.
         */
        @Volatile
        var recordingStopper: (() -> Unit)? = null

        private const val CHANNEL_ID = "bubble"
        private const val NOTIF_ID = 1
    }

    private lateinit var wm: WindowManager
    private var container: FrameLayout? = null
    private var iconView: ImageView? = null
    private var waveView: WaveformView? = null
    private lateinit var params: WindowManager.LayoutParams
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pulse: ValueAnimator? = null

    private var dismissView: View? = null
    private var overlayType = 0
    private var bubbleSize = 0
    private var dismissSize = 0
    private var overDismiss = false

    // Field-gated visibility: when on (and accessibility is available), the bubble
    // only appears while a text field is focused — like a contextual keyboard key.
    @Volatile private var gateSetting = true
    private var fieldFocused = false
    private var recording = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            startForeground(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
            android.util.Log.e("BubbleService", "startForeground failed", e)
        }
        gateSetting = try {
            runBlocking { SettingsRepository(applicationContext).get().bubbleOnlyOnFields }
        } catch (_: Exception) { true }
        addBubble()
        isRunning = true
        instance = this
        // If gated, start hidden until the accessibility service reports a focused field.
        applyGating(animate = false)
        // In case a field is already focused when the bubble starts, ask for a check.
        OpenWisprAccessibilityService.reevaluate()
        warmModels()
    }

    /**
     * Best-effort cold-start warmup so the first dictation doesn't stall on model loads.
     * Only touches on-device paths (cloud needs no warmup) and never crashes the service.
     *  - Whisper: preloads the cached context (safe — it's reused across dictations).
     *  - LLM: constructs the native engine singleton (loads libai-chat + native init). We
     *    do NOT pre-load the GGUF; LocalLlmEngine reloads it fresh per call by design.
     */
    private fun warmModels() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val s = SettingsRepository(applicationContext).get()
                if (s.sttProvider == "local") {
                    OnDeviceStt.warm(applicationContext, s.sttModel)
                }
                if (s.provider == "local") {
                    AiChat.getInferenceEngine(applicationContext)
                }
            } catch (e: Exception) {
                android.util.Log.w("BubbleService", "warmup skipped", e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Floating bubble", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_aperture)
            .setContentTitle("OpenWispr")
            .setContentText("Tap a text field to dictate · long-press to transform")
            .setOngoing(true)
            .build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val size = (56 * density).toInt()
        val pad = (14 * density).toInt()

        bubbleSize = size
        val frame = FrameLayout(this).apply {
            background = ContextCompat.getDrawable(this@BubbleService, R.drawable.bubble_background)
            elevation = 8 * density
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_aperture)
            setPadding(pad, pad, pad, pad)
        }
        val wave = WaveformView(this).apply { visibility = View.GONE }
        frame.addView(icon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(wave, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        overlayType = type

        params = WindowManager.LayoutParams(
            size,
            size,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * density).toInt()
            y = (200 * density).toInt()
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longFired = false
        val touchSlop = 12 * density

        val longPress = Runnable {
            if (!moved && recordingStopper == null) {
                longFired = true
                vibrate(longArrayOf(0, 28, 50, 28)) // double tick distinguishes long-press
                launchRewrite(Defaults.MODE_TRANSFORM, autoRecord = false)
            }
        }

        frame.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    longFired = false
                    mainHandler.postDelayed(longPress, 450)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        moved = true
                        mainHandler.removeCallbacks(longPress)
                    }
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    if (moved) {
                        overDismiss = isOverDismiss()
                        showDismiss(overDismiss)
                        if (overDismiss) {
                            // Magnetize into the target for a clear "drop to remove" feel.
                            val (cx, cy) = dismissCenter()
                            params.x = (cx - bubbleSize / 2f).toInt()
                            params.y = (cy - bubbleSize / 2f).toInt()
                        }
                    }
                    container?.let { wm.updateViewLayout(it, params) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPress)
                    if (moved && overDismiss) {
                        vibrate(longArrayOf(0, 40))
                        stopSelf() // drag-to-dismiss
                        return@setOnTouchListener true
                    }
                    hideDismiss()
                    overDismiss = false
                    if (!moved && !longFired) onTap()
                    true
                }
                else -> false
            }
        }

        container = frame
        iconView = icon
        waveView = wave
        try {
            wm.addView(frame, params)
        } catch (e: Exception) {
            android.util.Log.e("BubbleService", "addView failed (overlay permission?)", e)
        }
        addDismissTarget()
    }

    // ---- drag-to-dismiss target (bottom center) ----

    @SuppressLint("InflateParams")
    private fun addDismissTarget() {
        val density = resources.displayMetrics.density
        dismissSize = (64 * density).toInt()
        val pad = (16 * density).toInt()
        val frame = FrameLayout(this).apply {
            background = ContextCompat.getDrawable(this@BubbleService, R.drawable.dismiss_background)
            visibility = View.GONE
        }
        val x = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setPadding(pad, pad, pad, pad)
        }
        frame.addView(x, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val p = WindowManager.LayoutParams(
            dismissSize,
            dismissSize,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (80 * density).toInt()
        }
        dismissView = frame
        try { wm.addView(frame, p) } catch (_: Exception) {}
    }

    /** Screen-space center of the dismiss target (top-left coordinate system). */
    private fun dismissCenter(): Pair<Float, Float> {
        val m = resources.displayMetrics
        val cx = m.widthPixels / 2f
        val cy = m.heightPixels - 80 * m.density - dismissSize / 2f
        return cx to cy
    }

    private fun isOverDismiss(): Boolean {
        val bubbleCx = params.x + bubbleSize / 2f
        val bubbleCy = params.y + bubbleSize / 2f
        val (cx, cy) = dismissCenter()
        val dx = bubbleCx - cx
        val dy = bubbleCy - cy
        return Math.hypot(dx.toDouble(), dy.toDouble()) < dismissSize * 1.1
    }

    private fun showDismiss(over: Boolean) {
        val v = dismissView ?: return
        v.visibility = View.VISIBLE
        v.background = ContextCompat.getDrawable(
            this,
            if (over) R.drawable.dismiss_background_active else R.drawable.dismiss_background,
        )
        val s = if (over) 1.3f else 1f
        v.scaleX = s; v.scaleY = s
    }

    private fun hideDismiss() {
        val v = dismissView ?: return
        v.visibility = View.GONE
        v.scaleX = 1f; v.scaleY = 1f
    }

    private fun onTap() {
        vibrate(longArrayOf(0, 18))
        val stop = recordingStopper
        if (stop != null) mainHandler.post(stop) // stop the in-progress recording
        else launchRewrite(Defaults.MODE_DICTATE)
    }

    private fun launchRewrite(mode: String, autoRecord: Boolean = true) {
        startActivity(
            Intent(this, RewriteActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(RewriteActivity.EXTRA_MODE, mode)
                .putExtra(RewriteActivity.EXTRA_AUTO_RECORD, autoRecord)
        )
    }

    // ---- called from RewriteActivity (same process / main thread) ----

    fun showRecording() {
        recording = true
        ensureVisible()
        container?.background =
            ContextCompat.getDrawable(this, R.drawable.bubble_background_recording)
        iconView?.visibility = View.GONE
        waveView?.visibility = View.VISIBLE
        startPulse()
    }

    fun showAmplitude(amp: Int) {
        waveView?.push(amp)
    }

    fun showIdle() {
        recording = false
        stopPulse()
        container?.background =
            ContextCompat.getDrawable(this, R.drawable.bubble_background)
        waveView?.visibility = View.GONE
        iconView?.visibility = View.VISIBLE
        applyGating(animate = true) // re-hide if gated and no field is focused
    }

    // ---- field-gated visibility (driven by the accessibility service) ----

    /** Whether visibility should track focused text fields (needs accessibility on). */
    private fun gateActive(): Boolean = gateSetting && OpenWisprAccessibilityService.isEnabled

    private fun shouldShow(): Boolean = recording || fieldFocused || !gateActive()

    /**
     * Called by the accessibility service when a host app's editable field gains or
     * loses input focus. No-op when gating is off (bubble is always visible then).
     */
    fun setFieldFocused(focused: Boolean) {
        mainHandler.post {
            fieldFocused = focused
            // Reconcile against the *actual* shown state (incl. alpha), so a transition
            // that left the view VISIBLE-but-transparent can't strand it hidden.
            if (shouldShow() != isShown()) applyGating(animate = true)
        }
    }

    /** Truly visible = on screen and not transparent (a hidden bubble can be VISIBLE w/ alpha 0). */
    private fun isShown(): Boolean {
        val c = container ?: return false
        return c.visibility == View.VISIBLE && c.alpha > 0.01f
    }

    /** Re-read the gating preference and apply (e.g. after accessibility connects). */
    fun refreshGating() {
        gateSetting = try {
            runBlocking { SettingsRepository(applicationContext).get().bubbleOnlyOnFields }
        } catch (_: Exception) { gateSetting }
        mainHandler.post { applyGating(animate = true) }
    }

    private fun ensureVisible() {
        val c = container ?: return
        if (c.visibility == View.VISIBLE && c.alpha == 1f) return
        c.visibility = View.VISIBLE
        c.animate().cancel()
        c.alpha = 1f; c.scaleX = 1f; c.scaleY = 1f
    }

    private fun applyGating(animate: Boolean) {
        val c = container ?: return
        c.animate().cancel() // drop any in-flight show/hide so they can't clobber us
        if (shouldShow()) {
            if (c.visibility == View.VISIBLE && c.alpha >= 1f) return
            if (c.visibility != View.VISIBLE) { c.alpha = 0f; c.scaleX = 0.6f; c.scaleY = 0.6f }
            c.visibility = View.VISIBLE
            if (animate && c.alpha < 1f) c.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170).start()
            else { c.alpha = 1f; c.scaleX = 1f; c.scaleY = 1f }
        } else {
            if (!isShown()) { c.visibility = View.GONE; return }
            if (animate) {
                c.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(140)
                    // Re-check intent at the end: a re-focus mid-animation must win, and
                    // must restore full opacity (never leave VISIBLE-but-transparent).
                    .withEndAction {
                        if (shouldShow()) { c.alpha = 1f; c.scaleX = 1f; c.scaleY = 1f }
                        else c.visibility = View.GONE
                    }.start()
            } else { c.alpha = 0f; c.visibility = View.GONE }
        }
    }

    /** Gentle breathing animation while recording. */
    private fun startPulse() {
        stopPulse()
        val c = container ?: return
        pulse = ValueAnimator.ofFloat(1f, 1.12f).apply {
            duration = 650
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { a ->
                val v = a.animatedValue as Float
                c.scaleX = v; c.scaleY = v
            }
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        container?.scaleX = 1f
        container?.scaleY = 1f
    }

    private fun vibrate(timings: LongArray) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VibratorManager::class.java))?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        } ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(timings, -1)
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        stopPulse()
        container?.let { v -> try { wm.removeView(v) } catch (_: Exception) {} }
        dismissView?.let { v -> try { wm.removeView(v) } catch (_: Exception) {} }
        container = null
        iconView = null
        waveView = null
        dismissView = null
        isRunning = false
        if (instance === this) instance = null
    }
}
