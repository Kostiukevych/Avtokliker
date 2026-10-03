package com.example.autoclicker.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import com.example.autoclicker.data.ClickerSettings

/**
 * Полноэкранный прозрачный оверлей с флагом FLAG_NOT_TOUCHABLE.
 * 1. Отображает постоянные цветные метки P1, P2, P3 на координатах точек.
 * 2. Показывает визуальное подтверждение нажатий (анимация волны 400мс и статус).
 * Никогда не перехватывает касания пользователя и игры.
 */
@SuppressLint("ViewConstructor")
class PointsVisualOverlayView(context: Context) : View(context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    var showPoints: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private var currentSettings: ClickerSettings? = null

    // Настройки рисования меток точек
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2f)
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#E610141E")
    }

    private val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1.5f)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dpToPx(11f)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private val strikePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0BEC5")
        strokeWidth = dpToPx(1.5f)
    }

    // Состояние анимации обратной связи (Tap Ripple Feedback)
    private var ripplePointId: Int = 0
    private var rippleX: Float = 0f
    private var rippleY: Float = 0f
    private var rippleSuccess: Boolean = true
    private var rippleLabel: String = ""
    private var rippleProgress: Float = 1f // 0f -> 1f (1f = завершено)

    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val rippleBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val rippleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dpToPx(12f)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private var rippleAnimator: ValueAnimator? = null

    fun attachToWindow(): WindowManager.LayoutParams {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        windowManager.addView(this, params)
        return params
    }

    fun cancelAnimations() {
        post {
            rippleAnimator?.cancel()
            rippleProgress = 1f
            invalidate()
        }
    }

    fun detachFromWindow() {
        rippleAnimator?.cancel()
        try {
            windowManager.removeView(this)
        } catch (_: Exception) {}
    }

    fun updateSettings(settings: ClickerSettings) {
        this.currentSettings = settings
        postInvalidate()
    }

    /**
     * Показ анимации волны расширяющегося кольца на 400мс.
     */
    fun showTapRipple(
        pointId: Int,
        x: Float,
        y: Float,
        success: Boolean,
        tapIndex: Int = 1,
        totalTaps: Int = 1
    ) {
        post {
            rippleAnimator?.cancel()
            ripplePointId = pointId
            rippleX = x
            rippleY = y
            rippleSuccess = success
            rippleLabel = "P$pointId · $tapIndex/$totalTaps"

            rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 400L
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    rippleProgress = anim.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Рисуем постоянные метки точек (1..10)
        if (showPoints) {
            val settings = currentSettings
            if (settings != null) {
                val pointColors = intArrayOf(
                    Color.parseColor("#00E5FF"), // 1
                    Color.parseColor("#FFAB00"), // 2
                    Color.parseColor("#00E676"), // 3
                    Color.parseColor("#E040FB"), // 4
                    Color.parseColor("#FF6E40"), // 5
                    Color.parseColor("#40C4FF"), // 6
                    Color.parseColor("#FFD740"), // 7
                    Color.parseColor("#B388FF"), // 8
                    Color.parseColor("#69F0AE"), // 9
                    Color.parseColor("#FF5252")  // 10
                )
                for (point in settings.allPoints) {
                    val colorIndex = (point.id - 1).coerceIn(0, pointColors.size - 1)
                    drawPointMarker(canvas, point, point.id, pointColors[colorIndex])
                }
            }
        }

        // 2. Рисуем анимацию волны нажатия
        if (rippleProgress < 1f) {
            val baseColor = if (rippleSuccess) Color.parseColor("#00E676") else Color.parseColor("#FF1744")
            val alpha = ((1f - rippleProgress) * 255).toInt().coerceIn(0, 255)

            // Расширяющееся кольцо
            val startRadius = dpToPx(14f)
            val endRadius = dpToPx(60f)
            val currentRadius = startRadius + (endRadius - startRadius) * rippleProgress

            ripplePaint.color = baseColor
            ripplePaint.alpha = alpha
            ripplePaint.strokeWidth = dpToPx(3f) * (1f - rippleProgress * 0.5f)
            canvas.drawCircle(rippleX, rippleY, currentRadius, ripplePaint)

            // Маленький центр нажатия
            dotPaint.color = baseColor
            dotPaint.alpha = alpha
            canvas.drawCircle(rippleX, rippleY, dpToPx(5f), dotPaint)

            // Бейдж с надписью над точкой
            val badgeW = dpToPx(85f)
            val badgeH = dpToPx(24f)
            val badgeX = rippleX
            val badgeY = rippleY - currentRadius - dpToPx(16f)

            val rect = RectF(
                badgeX - badgeW / 2f,
                badgeY - badgeH / 2f,
                badgeX + badgeW / 2f,
                badgeY + badgeH / 2f
            )

            rippleBadgePaint.color = baseColor
            rippleBadgePaint.alpha = alpha
            canvas.drawRoundRect(rect, dpToPx(6f), dpToPx(6f), rippleBadgePaint)

            rippleTextPaint.alpha = alpha
            val textBase = badgeY - (rippleTextPaint.descent() + rippleTextPaint.ascent()) / 2f
            canvas.drawText(rippleLabel, badgeX, textBase, rippleTextPaint)
        }
    }

    private fun drawPointMarker(
        canvas: Canvas,
        point: com.example.autoclicker.data.ClickPoint,
        pointId: Int,
        activeColor: Int
    ) {
        if (!point.isConfigured) return

        val isEnabled = point.enabled
        val markerColor = if (isEnabled) activeColor else Color.parseColor("#78909C")
        val alpha = if (isEnabled) 240 else 110

        val cx = point.x
        val cy = point.y

        // Внешнее кольцо прицела
        ringPaint.color = markerColor
        ringPaint.alpha = alpha
        val ringRadius = dpToPx(16f)
        canvas.drawCircle(cx, cy, ringRadius, ringPaint)

        // Центр точка
        dotPaint.color = markerColor
        dotPaint.alpha = alpha
        canvas.drawCircle(cx, cy, dpToPx(3f), dotPaint)

        // Волосковые линии прицела
        val lineLen = dpToPx(6f)
        canvas.drawLine(cx - ringRadius - lineLen, cy, cx - ringRadius + dpToPx(2f), cy, ringPaint)
        canvas.drawLine(cx + ringRadius - dpToPx(2f), cy, cx + ringRadius + lineLen, cy, ringPaint)
        canvas.drawLine(cx, cy - ringRadius - lineLen, cx, cy - ringRadius + dpToPx(2f), ringPaint)
        canvas.drawLine(cx, cy + ringRadius - dpToPx(2f), cx, cy + ringRadius + lineLen, ringPaint)

        // Плашка-бейдж с подписью "P1", "P2", "P3"
        val label = "P$pointId"
        val badgeW = dpToPx(28f)
        val badgeH = dpToPx(20f)
        val badgeY = cy - ringRadius - dpToPx(13f)

        val badgeRect = RectF(
            cx - badgeW / 2f,
            badgeY - badgeH / 2f,
            cx + badgeW / 2f,
            badgeY + badgeH / 2f
        )

        badgeBgPaint.alpha = alpha
        canvas.drawRoundRect(badgeRect, dpToPx(6f), dpToPx(6f), badgeBgPaint)

        badgeBorderPaint.color = markerColor
        badgeBorderPaint.alpha = alpha
        canvas.drawRoundRect(badgeRect, dpToPx(6f), dpToPx(6f), badgeBorderPaint)

        textPaint.color = if (isEnabled) Color.WHITE else Color.parseColor("#CFD8DC")
        textPaint.alpha = alpha
        val textBase = badgeY - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, cx, textBase, textPaint)

        // Если точка выключена — зачёркивание
        if (!isEnabled) {
            strikePaint.alpha = alpha
            canvas.drawLine(
                badgeRect.left + dpToPx(3f),
                badgeRect.bottom - dpToPx(3f),
                badgeRect.right - dpToPx(3f),
                badgeRect.top + dpToPx(3f),
                strikePaint
            )
        }
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }
}
