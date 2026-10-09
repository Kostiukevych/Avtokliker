package com.example.autoclicker.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/**
 * Плавающая кнопка «чёрная дыра»:
 * — нижнее анимированное кольцо (под кнопкой)
 * — чёрное ядро; при isRunning — зелёный круглый контур ядра
 * — верхнее анимированное кольцо (поверх) — тот же цвет, что и нижнее
 */
class BlackHoleBadgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var isRunning: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private var angle = 0f

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val ringColors = intArrayOf(
        0x00FFE2B8.toInt(),
        0xFFFFC107.toInt(),
        0xFFFF6D00.toInt(),
        0xFFE040FB.toInt(),
        0xFF00E5FF.toInt(),
        0x00FFE2B8.toInt()
    )

    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 6000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            angle = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f * 0.94f
        val coreR = r * 0.48f

        // Свечение
        glowPaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(0x4400E5FF.toInt(), Color.TRANSPARENT),
            floatArrayOf(0.4f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, glowPaint)
        glowPaint.shader = null

        // ── 1. Нижнее кольцо (под кнопкой) ──
        ringPaint.strokeWidth = r * 0.13f
        ringPaint.shader = SweepGradient(cx, cy, ringColors, null)
        canvas.save()
        canvas.rotate(angle, cx, cy)
        canvas.drawCircle(cx, cy, r * 0.86f, ringPaint)
        canvas.restore()
        ringPaint.shader = null

        // ── 2. Чёрное ядро ──
        corePaint.style = Paint.Style.FILL
        corePaint.color = Color.BLACK
        canvas.drawCircle(cx, cy, coreR, corePaint)

        // Зелёный круглый контур ядра — только когда режим активен
        if (isRunning) {
            val phase = angle / 360f * 4.5f
            val pulse = (0.75f + 0.25f * (0.5f + 0.5f * sin(phase * Math.PI * 2).toFloat()))
                .coerceIn(0.75f, 1f)
            val a = (pulse * 255).toInt().coerceIn(190, 255)

            corePaint.style = Paint.Style.STROKE
            // мягкое свечение контура
            corePaint.color = Color.argb(0x50, 0x39, 0xFF, 0x14)
            corePaint.strokeWidth = dp(7f)
            canvas.drawCircle(cx, cy, coreR, corePaint)
            // яркий зелёный контур круга
            corePaint.color = Color.argb(a, 0x39, 0xFF, 0x14)
            corePaint.strokeWidth = dp(2.8f)
            canvas.drawCircle(cx, cy, coreR, corePaint)
        } else {
            corePaint.style = Paint.Style.STROKE
            corePaint.color = 0x40B0BEC5.toInt()
            corePaint.strokeWidth = dp(1.6f)
            canvas.drawCircle(cx, cy, coreR, corePaint)
        }

        // ── 3. Верхнее кольцо (поверх кнопки) — тот же цвет, что нижнее ──
        ringPaint.strokeWidth = r * 0.08f
        ringPaint.shader = SweepGradient(cx, cy, ringColors, null)
        canvas.save()
        canvas.rotate(-angle * 1.35f, cx, cy)
        canvas.drawCircle(cx, cy, coreR * 0.92f, ringPaint)
        canvas.restore()
        ringPaint.shader = null
    }
}
