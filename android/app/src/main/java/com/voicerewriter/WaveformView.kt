package com.voicerewriter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Tiny live waveform: a row of vertical bars whose heights follow the most recent
 * microphone amplitudes. Drawn white on the (red) recording bubble background.
 * Feed it amplitudes via [push]; it animates itself while attached.
 */
class WaveformView(context: Context) : View(context) {

    private val barCount = 5
    private val levels = FloatArray(barCount) { 0.15f } // 0..1 per bar
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FCF8F4") // brand cream
        style = Paint.Style.FILL
    }
    private val rect = RectF()

    /** Push a fresh amplitude (0..32767); shifts bars left and animates. */
    fun push(amplitude: Int) {
        val norm = (amplitude / 14000f).coerceIn(0f, 1f)
        for (i in 0 until barCount - 1) levels[i] = levels[i + 1]
        levels[barCount - 1] = max(0.15f, norm)
        invalidate()
    }

    /**
     * Processing, not listening: the bars run a slow travelling wave of their own rather
     * than following the microphone. Deliberately the same five bars as [push] — the bubble
     * shouldn't visually jump when recording ends, it should just change rhythm.
     *
     * [phase] cycles 0..1. Shallower and slower than speech: this is "working", not "hearing".
     */
    fun setWorkingPhase(phase: Float) {
        for (i in 0 until barCount) {
            val a = sin((phase + i * 0.14f) * 2f * PI.toFloat())
            levels[i] = 0.2f + 0.34f * (a * 0.5f + 0.5f)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = w * 0.18f
        val usable = w - pad * 2
        val gap = usable * 0.06f
        val barW = (usable - gap * (barCount - 1)) / barCount
        val radius = barW / 2f
        val cy = h / 2f
        val maxBar = h * 0.62f
        for (i in 0 until barCount) {
            val bh = max(barW, levels[i] * maxBar)
            val left = pad + i * (barW + gap)
            rect.set(left, cy - bh / 2f, left + barW, cy + bh / 2f)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }
}
