package com.voicerewriter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.max

/**
 * Tiny live waveform: a row of vertical bars whose heights follow the most recent
 * microphone amplitudes. Drawn white on the (red) recording bubble background.
 * Feed it amplitudes via [push]; it animates itself while attached.
 */
class WaveformView(context: Context) : View(context) {

    private val barCount = 5
    private val levels = FloatArray(barCount) { 0.15f } // 0..1 per bar
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
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
