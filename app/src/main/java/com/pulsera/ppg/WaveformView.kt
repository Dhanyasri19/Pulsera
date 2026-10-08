package com.pulsera.ppg

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Lightweight custom view that draws a normalized time-series signal
 * (used for both the raw and filtered PPG waveform).
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var samples: List<Float> = emptyList()

    private val linePaint = Paint().apply {
        color = Color.parseColor("#3D7EFF")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val midlinePaint = Paint().apply {
        color = Color.parseColor("#2A2E37")
        strokeWidth = 2f
    }

    fun setSamples(newSamples: List<Float>) {
        samples = newSamples
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawLine(0f, h / 2f, w, h / 2f, midlinePaint)

        if (samples.size < 2) return

        val minV = samples.min()
        val maxV = samples.max()
        val range = (maxV - minV).takeIf { it > 0.0001f } ?: 1f

        val stepX = w / (samples.size - 1)
        var prevX = 0f
        var prevY = h - ((samples[0] - minV) / range) * h

        for (i in 1 until samples.size) {
            val x = i * stepX
            val y = h - ((samples[i] - minV) / range) * h
            canvas.drawLine(prevX, prevY, x, y, linePaint)
            prevX = x
            prevY = y
        }
    }
}
