package com.example.autoclicker.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Свёрнутая плавающая кнопка — чёрная дыра без фона/текста.
 * showPlay=true → треугольник, false → квадрат.
 */
class BlackHoleBadgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var showPlay: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var isRunning: Boolean
        get() = !showPlay
        set(value) {
            showPlay = !value
        }

    fun setTextColor(color: Int) {
        // Compatibility with FloatingOverlayService
    }

    private var angle = 0f
    private val diskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val lensPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xCCFFE2B8.toInt()
        strokeWidth = 2.5f
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFF6E6.toInt()
        style = Paint.Style.FILL
    }
    private val triPath = Path()

    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 7000
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

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f * 0.92f

        glowPaint.shader = RadialGradient(
            cx, cy, r * 1.1f,
            intArrayOf(0x66FF8A2A.toInt(), 0x22FF5A14.toInt(), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r * 1.05f, glowPaint)

        canvas.save()
        canvas.rotate(-14f, cx, cy)
        canvas.scale(1f, 0.28f, cx, cy)
        canvas.rotate(angle, cx, cy)
        diskPaint.strokeWidth = r * 0.22f
        diskPaint.shader = SweepGradient(
            cx, cy,
            intArrayOf(
                0xFFB43A0A.toInt(), 0xFFFF7A18.toInt(), 0xFFFFD27A.toInt(), 0xFFFFF6E6.toInt(),
                0xFFFFD27A.toInt(), 0xFFFF7A18.toInt(), 0xFF4A1200.toInt(), 0xFFB43A0A.toInt()
            ),
            null
        )
        canvas.drawCircle(cx, cy, r * 0.92f, diskPaint)
        canvas.restore()

        canvas.save()
        canvas.rotate(-angle * 0.62f, cx, cy)
        lensPaint.strokeWidth = r * 0.06f
        lensPaint.shader = SweepGradient(
            cx, cy,
            intArrayOf(
                0xFFFF7A18.toInt(), 0xFFFFD27A.toInt(), 0xFFFFF6E6.toInt(),
                0xFFFFD27A.toInt(), 0xFFFF7A18.toInt(), 0xFFB43A0A.toInt(), 0xFFFF7A18.toInt()
            ),
            null
        )
        canvas.drawCircle(cx, cy, r * 0.42f, lensPaint)
        canvas.restore()

        canvas.drawCircle(cx, cy, r * 0.36f, corePaint)
        canvas.drawCircle(cx, cy, r * 0.36f, ringPaint)

        if (showPlay) {
            val s = r * 0.18f
            triPath.reset()
            triPath.moveTo(cx - s * 0.55f, cy - s)
            triPath.lineTo(cx + s * 0.95f, cy)
            triPath.lineTo(cx - s * 0.55f, cy + s)
            triPath.close()
            canvas.drawPath(triPath, iconPaint)
        } else {
            val s = r * 0.22f
            canvas.drawRect(cx - s, cy - s, cx + s, cy + s, iconPaint)
        }
    }
}
