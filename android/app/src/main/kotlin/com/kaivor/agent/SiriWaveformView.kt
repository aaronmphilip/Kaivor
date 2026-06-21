package com.kaivor.agent

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/**
 * iOS Siri-style animated waveform bars for the collapsed notch state.
 */
class SiriWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }

    private val barCount = 5
    private val barRects = Array(barCount) { RectF() }
    private var phase = 0f
    private var animator: ValueAnimator? = null
    private var active = true

    fun setActive(running: Boolean) {
        active = running
        if (running) startAnim() else stopAnim()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (active) startAnim()
    }

    override fun onDetachedFromWindow() {
        stopAnim()
        super.onDetachedFromWindow()
    }

    private fun startAnim() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 6.28f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopAnim() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val gap = w * 0.08f
        val barW = (w - gap * (barCount - 1)) / barCount
        val mid = h / 2f

        for (i in 0 until barCount) {
            val norm = if (active) {
                0.35f + 0.65f * ((sin(phase + i * 1.15f) + 1f) / 2f)
            } else {
                0.22f
            }
            val barH = h * norm
            val left = i * (barW + gap)
            val top = mid - barH / 2f
            barRects[i].set(left, top, left + barW, top + barH)
            paint.alpha = if (active) 220 else 100
            canvas.drawRoundRect(barRects[i], barW / 2f, barW / 2f, paint)
        }
    }
}