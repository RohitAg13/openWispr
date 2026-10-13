package com.voicerewriter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The bubble itself: one view that draws all six states and animates between them.
 *
 * Replaces the old FrameLayout + background-drawable + ImageView + WaveformView stack. The
 * states no longer differ only in fill colour, so they can't be expressed as swappable
 * drawables any more: the design gives each one a different *shape* (48px circle vs 112x48
 * pill), a different fill, and its own motion, and three of them draw outside the content
 * box (shadow, expanding halo). One canvas is both simpler and cheaper than six drawables
 * plus two child views.
 *
 * ## Why the view is bigger than the bubble
 *
 * The view fills the whole overlay window and insets its content by [PAD_DP] on every side.
 * That transparent margin is where the drop shadow and the listening halo are drawn — a view
 * cannot paint outside its own bounds, and an elevation shadow would be clipped by the window
 * either way. [BubbleService] sizes the window to `content + 2 * PAD_DP` and keeps the docked
 * edge pinned, so the padding is invisible and hangs off the side of the screen.
 *
 * ## Why a software layer
 *
 * [Paint.setShadowLayer] is the only way to get a shadow for a shape drawn on a canvas, and it
 * is unreliable on a hardware-accelerated canvas for anything but text. The view is at most
 * 140x76dp, so rasterising it in software costs ~100k pixels a frame — cheap enough that the
 * honest shadow is worth more than the GPU path.
 *
 * ## Motion
 *
 * Four states animate forever (listening, processing) or once (inserted, copied). Rather than
 * one [android.animation.ValueAnimator] per effect, every phase is derived from the time since
 * the state was entered, and [onDraw] re-posts itself with [postInvalidateOnAnimation] while
 * the current state has anything moving. One clock, no animators to leak, and a state change
 * resets every phase at once so nothing is ever caught mid-cycle from the state before.
 */
class BubbleView(context: Context) : View(context) {

    enum class State {
        /** Default. Small, translucent, dimmed — see [dim]. */
        IDLE,

        /** Picked up for a drag: opaque, coral outline, lifted off the surface. */
        LIFTED,

        /** The only warm, filled state. Pill, live waveform, breathing halo. */
        LISTENING,

        /** Transcribing and polishing. Three drifting dots over a slow glow — a pause, not a wait. */
        PROCESSING,

        /** The text went into the field. Green check, draws itself in, auto-returns. */
        INSERTED,

        /** No field to write into, so the text went to the clipboard. Pill, says so in words. */
        COPIED,
    }

    companion object {
        const val HEIGHT_DP = 48f
        const val WIDTH_SMALL_DP = 48f

        /**
         * The widest the bubble ever gets. The design's hard rule: never bigger than a thumb,
         * and it grows *inward* from the screen edge rather than out over the middle.
         */
        const val WIDTH_PILL_DP = 112f

        /** Transparent room around the content, for the shadow and the halo. */
        const val PAD_DP = 10f

        /** Which states are pills rather than circles. Drives the window width. */
        fun widthDpFor(state: State): Float =
            if (state == State.LISTENING || state == State.COPIED) WIDTH_PILL_DP else WIDTH_SMALL_DP

        private const val BAR_COUNT = 6

        /** Shortest a waveform bar may draw, as a fraction of its full height. See [pushAmplitude]. */
        private const val QUIET_BAR = 0.20f



        // --- Palette, converted from the design's OKLCH values ---
        // Light
        private const val SURFACE = 0xFFFFFDF9.toInt()
        private const val EDGE = 0xFFE0D6CA.toInt()
        private const val MARK = 0xFFCD6E4C.toInt()
        private const val LIFT_EDGE = 0xFFDE8D6A.toInt()
        private const val GLOW_LIGHT = 0xFFFFCCA1.toInt()
        private const val COPIED_BG_LIGHT = 0xFF3B2921.toInt()
        private const val COPIED_ICON_LIGHT = 0xFFA1E4AE.toInt()
        private const val COPIED_TEXT_LIGHT = 0xFFFFFBF4.toInt()
        // Dark — a charcoal surface and a brighter mark, so it doesn't vanish on dark screens.
        private const val SURFACE_DK = 0xFF2F231E.toInt()
        private const val EDGE_DK = 0xFF594941.toInt()
        private const val MARK_DK = 0xFFF4A16D.toInt()
        private const val GLOW_DK = 0xFFB46B40.toInt()
        private const val COPIED_BG_DK = 0xFFF6F1E9.toInt()
        private const val COPIED_ICON_DK = 0xFF137738.toInt()
        private const val COPIED_TEXT_DK = 0xFF36241D.toInt()
        // Shared
        private const val LISTEN_FROM = 0xFFF4AD67.toInt()
        private const val LISTEN_TO = 0xFFD86A4D.toInt()
        private const val HALO = 0xFFE67D58.toInt()
        private const val CREAM = 0xFFFFFBF4.toInt()
        private const val GREEN = 0xFF3B9555.toInt()
    }

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(2.8f) * (22f / 24f) // design: 2.8 in a 24 viewBox rendered at 22dp
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(13f)
        typeface = runCatching { ResourcesCompat.getFont(context, R.font.mulish_semibold) }
            .getOrNull() ?: Typeface.DEFAULT_BOLD
    }

    private val content = RectF()
    private val scratch = RectF()
    private val checkPath = Path()
    private val checkSegment = Path()
    private val pathMeasure = PathMeasure()

    private val mark: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_aperture)
    private val clipboard: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_clipboard)

    /** Current state. Setting it restarts the motion clock; see the class docs. */
    var state: State = State.IDLE
        set(value) {
            if (field == value) return
            field = value
            enteredAt = SystemClock.uptimeMillis()
            if (value == State.LISTENING) resetBars()
            invalidate()
        }

    /**
     * Whether the host screen is dark. Not the app's own theme — the bubble floats over other
     * apps, and we cannot sample what is behind it, so the system dark-mode setting is the best
     * available proxy for "the screen under me is dark". Set by [BubbleService], which also
     * refreshes it on a configuration change.
     */
    var dark: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }

    /** Whether idle fades back. The design's default; exposed so it stays a one-line change. */
    var dim: Boolean = true
        set(value) { if (field != value) { field = value; invalidate() } }

    private var enteredAt = SystemClock.uptimeMillis()
    private val bars = FloatArray(BAR_COUNT)

    init {
        // See "Why a software layer" above.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        resetBars()
    }

    /** The resting waveform from the design, so the pill never opens on a flat line. */
    private fun resetBars() {
        val rest = floatArrayOf(0.40f, 0.73f, 1.00f, 0.67f, 0.87f, 0.47f)
        for (i in 0 until BAR_COUNT) bars[i] = rest[i]
    }

    /**
     * Push a fresh microphone amplitude (0..32767); shifts the bars left.
     *
     * The floor is deliberately high. A bar shorter than it is wide draws as a circle, so a
     * floor near zero turned a silent room into a row of six evenly spaced dots — which is
     * both wrong (it reads as a progress indicator, not a level meter) and, worse, nearly the
     * processing state. The design's rule is that no two states may look alike, and silence is
     * exactly when the user most needs to know the microphone is still open.
     */
    fun pushAmplitude(amplitude: Int) {
        val norm = AudioRecorder.level(amplitude)
        for (i in 0 until BAR_COUNT - 1) bars[i] = bars[i + 1]
        bars[BAR_COUNT - 1] = max(QUIET_BAR, norm)
        invalidate()
    }

    private fun surface() = if (dark) SURFACE_DK else SURFACE
    private fun edge() = if (dark) EDGE_DK else EDGE
    private fun markColor() = if (dark) MARK_DK else MARK

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = dp(PAD_DP)
        content.set(pad, pad, width - pad, height - pad)
        if (content.isEmpty) return
        val t = (SystemClock.uptimeMillis() - enteredAt) / 1000f

        when (state) {
            State.IDLE -> drawQuiet(canvas, dimmed = dim)
            State.LIFTED -> drawLifted(canvas)
            State.LISTENING -> drawListening(canvas, t)
            State.PROCESSING -> drawProcessing(canvas, t)
            State.INSERTED -> drawInserted(canvas, t)
            State.COPIED -> drawCopied(canvas, t)
        }

        if (state == State.LISTENING || state == State.PROCESSING ||
            (state == State.INSERTED && t < 0.6f) || (state == State.COPIED && t < 0.4f)
        ) postInvalidateOnAnimation()
    }

    /** Shadow on [fillPaint]. Approximates the design's `0 Ypx Bpx -Spx` shadows. */
    private fun shadow(blurDp: Float, dyDp: Float, color: Int) {
        fillPaint.setShadowLayer(dp(blurDp), 0f, dp(dyDp), color)
    }

    private fun roundRect(canvas: Canvas, r: RectF, paint: Paint) {
        val radius = r.height() / 2f
        canvas.drawRoundRect(r, radius, radius, paint)
    }

    /** Idle and (undimmed) the shape everything else starts from: cream disc, hairline edge. */
    private fun drawQuiet(canvas: Canvas, dimmed: Boolean) {
        val alpha = if (!dimmed) 255 else if (dark) 204 else 184 // design: 0.8 dark / 0.72 light
        val layer = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), alpha)
        fillPaint.shader = null
        fillPaint.color = surface()
        shadow(9f, 4f, if (dark) 0xA6000000.toInt() else 0x4D5A4232)
        roundRect(canvas, content, fillPaint)
        fillPaint.clearShadowLayer()
        edgePaint.color = edge()
        edgePaint.strokeWidth = dp(1f)
        scratch.set(content)
        scratch.inset(dp(0.5f), dp(0.5f))
        roundRect(canvas, scratch, edgePaint)
        drawMark(canvas, markColor())
        canvas.restoreToCount(layer)
    }

    /**
     * Picked up for a drag. Opaque, coral outline, scaled up and thrown further off the
     * surface — three cues that the bubble is in your hand and not just under your finger.
     */
    private fun drawLifted(canvas: Canvas) {
        val save = canvas.save()
        canvas.scale(1.12f, 1.12f, content.centerX(), content.centerY())
        fillPaint.shader = null
        fillPaint.color = surface()
        shadow(14f, 7f, if (dark) 0xB3000000.toInt() else 0x735A4232)
        roundRect(canvas, content, fillPaint)
        fillPaint.clearShadowLayer()
        edgePaint.color = LIFT_EDGE
        edgePaint.strokeWidth = dp(1.5f)
        scratch.set(content)
        scratch.inset(dp(0.75f), dp(0.75f))
        roundRect(canvas, scratch, edgePaint)
        drawMark(canvas, markColor())
        canvas.restoreToCount(save)
    }

    private fun drawMark(canvas: Canvas, tint: Int) {
        val m = mark ?: return
        val size = dp(22f)
        val l = (content.centerX() - size / 2f).toInt()
        val top = (content.centerY() - size / 2f).toInt()
        m.setBounds(l, top, l + size.toInt(), top + size.toInt())
        m.setTint(tint)
        m.draw(canvas)
    }

    /**
     * Listening. The sunset fill is reserved for this one state — it is the only time the
     * bubble is warm and filled, which is what makes "we are recording you" unmistakable at a
     * glance and from the corner of the eye.
     */
    private fun drawListening(canvas: Canvas, t: Float) {
        // Halo: a ring that grows into the padding and fades out, once every 1.6s. Its travel
        // is the padding itself rather than the design's scale factors, so it can never be
        // clipped by the window edge no matter how wide the pill is.
        val haloT = (t % 1.6f) / 1.6f
        val grow = dp(PAD_DP) * haloT
        haloPaint.color = HALO
        haloPaint.alpha = (0.55f * (1f - haloT) * 255).toInt()
        scratch.set(content)
        scratch.inset(-grow, -grow)
        roundRect(canvas, scratch, haloPaint)

        // Sunset fill. CSS `linear-gradient(140deg, ...)`: the gradient line points 140deg
        // clockwise from straight up, i.e. (sin a, -cos a).
        val a = Math.toRadians(140.0)
        val dx = sin(a).toFloat()
        val dy = -cos(a).toFloat()
        val len = abs(content.width() * dx) + abs(content.height() * dy)
        val cx = content.centerX()
        val cy = content.centerY()
        fillPaint.shader = LinearGradient(
            cx - dx * len / 2f, cy - dy * len / 2f,
            cx + dx * len / 2f, cy + dy * len / 2f,
            LISTEN_FROM, LISTEN_TO, Shader.TileMode.CLAMP,
        )
        fillPaint.color = Color.WHITE
        shadow(11f, 5f, 0x99A24A2E.toInt())
        roundRect(canvas, content, fillPaint)
        fillPaint.clearShadowLayer()
        fillPaint.shader = null

        val inset = dp(14f)
        accentPaint.color = CREAM

        // Breathing dot, left. Says "live" even in the moment between words, when the bars sit flat.
        val breathe = 0.45f + 0.40f * (0.5f + 0.5f * sin(t / 1.2f * 2f * Math.PI.toFloat()))
        accentPaint.alpha = (breathe * 255).toInt()
        canvas.drawCircle(content.left + inset + dp(4.5f), cy, dp(4.5f), accentPaint)
        accentPaint.alpha = 255

        // Waveform, right-aligned. Six bars, newest on the right.
        val barW = dp(4f)
        val gap = dp(4f)
        val maxH = dp(30f)
        val totalW = BAR_COUNT * barW + (BAR_COUNT - 1) * gap
        var x = content.right - inset - totalW
        for (i in 0 until BAR_COUNT) {
            val h = max(barW, bars[i] * maxH)
            scratch.set(x, cy - h / 2f, x + barW, cy + h / 2f)
            canvas.drawRoundRect(scratch, barW / 2f, barW / 2f, accentPaint)
            x += barW + gap
        }
    }

    /**
     * Transcribing and polishing. Deliberately not a spinner and deliberately not the
     * waveform: the bars mean "I can hear you", and leaving them running after the microphone
     * is closed says something false. Three slow dots over a breathing glow say "thinking"
     * without implying a measured countdown — you see this many times a day.
     */
    private fun drawProcessing(canvas: Canvas, t: Float) {
        fillPaint.shader = null
        fillPaint.color = surface()
        shadow(9f, 4f, if (dark) 0xA6000000.toInt() else 0x4D5A4232)
        roundRect(canvas, content, fillPaint)
        fillPaint.clearShadowLayer()
        edgePaint.color = edge()
        edgePaint.strokeWidth = dp(1f)
        scratch.set(content)
        scratch.inset(dp(0.5f), dp(0.5f))
        roundRect(canvas, scratch, edgePaint)

        val cx = content.centerX()
        val cy = content.centerY()

        // Glow: a warm disc that swells and fades on its own 2.4s cycle, slower than the dots,
        // so the two never line up into a pulse.
        val gT = 0.5f - 0.5f * cos(t / 2.4f * 2f * Math.PI.toFloat())
        val radius = (content.height() / 2f - dp(4f)) * (0.78f + 0.22f * gT)
        val glowAlpha = (0.35f + 0.50f * gT) * (if (dark) 0.55f else 0.9f)
        glowPaint.shader = RadialGradient(
            cx, cy, max(1f, radius),
            Color.argb((glowAlpha * 255).toInt(), Color.red(if (dark) GLOW_DK else GLOW_LIGHT),
                Color.green(if (dark) GLOW_DK else GLOW_LIGHT),
                Color.blue(if (dark) GLOW_DK else GLOW_LIGHT)),
            Color.TRANSPARENT, Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, glowPaint)
        glowPaint.shader = null

        // Three dots, each a fifth of a second behind the last.
        val dotR = dp(2.75f)
        val gap = dp(4f)
        val step = dotR * 2 + gap
        accentPaint.color = if (dark) MARK_DK else MARK
        for (i in 0 until 3) {
            val phase = (t / 1.5f + i * (0.2f / 1.5f)) * 2f * Math.PI.toFloat()
            val s = 0.5f + 0.5f * sin(phase)
            val dy = dp(1.5f) - dp(4f) * s
            accentPaint.alpha = ((0.4f + 0.6f * s) * 255).toInt()
            canvas.drawCircle(cx + (i - 1) * step, cy + dy, dotR, accentPaint)
        }
        accentPaint.alpha = 255
    }

    /** Inserted. Pops once, draws the check in, and [BubbleService] returns it to idle. */
    private fun drawInserted(canvas: Canvas, t: Float) {
        val save = canvas.save()
        canvas.scale(pop(t), pop(t), content.centerX(), content.centerY())
        fillPaint.shader = null
        fillPaint.color = GREEN
        shadow(10f, 5f, 0x8C2E6B3E.toInt())
        roundRect(canvas, content, fillPaint)
        fillPaint.clearShadowLayer()

        // Check, from the design's 24-unit viewBox rendered at 22dp, drawn in over 0.35s
        // starting 0.08s in — so the shape lands after the pop, not during it.
        val size = dp(22f)
        val ox = content.centerX() - size / 2f
        val oy = content.centerY() - size / 2f
        val u = size / 24f
        checkPath.reset()
        checkPath.moveTo(ox + 4.5f * u, oy + 12.5f * u)
        checkPath.lineTo(ox + 9.5f * u, oy + 17.5f * u)
        checkPath.lineTo(ox + 19.5f * u, oy + 7f * u)
        val drawT = ((t - 0.08f) / 0.35f).coerceIn(0f, 1f)
        checkPaint.color = CREAM
        pathMeasure.setPath(checkPath, false)
        checkSegment.reset()
        pathMeasure.getSegment(0f, pathMeasure.length * drawT, checkSegment, true)
        canvas.drawPath(checkSegment, checkPaint)
        canvas.restoreToCount(save)
    }

    /**
     * Copied. The one state that says something in words, because it is the one state the user
     * has to act on: the text is not where they were looking. See
     * [BubbleService.showClipboardFallback] for why the bubble carries this and a toast cannot.
     */
    private fun drawCopied(canvas: Canvas, t: Float) {
        val save = canvas.save()
        canvas.scale(pop(t), pop(t), content.centerX(), content.centerY())
        fillPaint.shader = null
        fillPaint.color = if (dark) COPIED_BG_DK else COPIED_BG_LIGHT
        shadow(10f, 5f, if (dark) 0x99000000.toInt() else 0x80231A14.toInt())
        roundRect(canvas, content, fillPaint)
        fillPaint.clearShadowLayer()

        val iconSize = dp(17f)
        val gap = dp(7f)
        val text = "Copied"
        labelPaint.color = if (dark) COPIED_TEXT_DK else COPIED_TEXT_LIGHT
        val textW = labelPaint.measureText(text)
        var x = content.centerX() - (iconSize + gap + textW) / 2f
        clipboard?.let { c ->
            val top = (content.centerY() - iconSize / 2f).toInt()
            c.setBounds(x.toInt(), top, (x + iconSize).toInt(), top + iconSize.toInt())
            c.setTint(if (dark) COPIED_ICON_DK else COPIED_ICON_LIGHT)
            c.draw(canvas)
        }
        x += iconSize + gap
        val fm = labelPaint.fontMetrics
        canvas.drawText(text, x, content.centerY() - (fm.ascent + fm.descent) / 2f, labelPaint)
        canvas.restoreToCount(save)
    }

    /** The design's `bw-pop`: 0.82 -> 1.08 -> 1 over 0.32s. */
    private fun pop(t: Float): Float {
        if (t >= 0.32f) return 1f
        val p = t / 0.32f
        return if (p < 0.6f) 0.82f + (1.08f - 0.82f) * (p / 0.6f)
        else 1.08f + (1f - 1.08f) * ((p - 0.6f) / 0.4f)
    }
}
