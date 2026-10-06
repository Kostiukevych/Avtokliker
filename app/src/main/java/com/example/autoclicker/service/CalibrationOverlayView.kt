package com.example.autoclicker.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.ui.theme.NeonTheme

/**
 * Полноэкранный оверлей калибровки точки и жестов с неоновым прицелом (.aim-layer)
 * и стеклянной плавающей панелью кнопок (.float-bar) в стиле liquid-glass-neon.
 */
@SuppressLint("ClickableViewAccessibility")
class CalibrationOverlayView(
    context: Context,
    private val currentPointId: Int,
    private val onCoordinateCaptured: ((Int, Float, Float) -> Unit)? = null,
    private val onDismissed: (() -> Unit)? = null
) {

    private val ctx: Context = context
    private val windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val settingsRepo = SettingsRepository.getInstance(ctx)
    private val density = ctx.resources.displayMetrics.density

    private var root: FrameLayout? = null
    private var markerView: MarkerView? = null
    private var panel: LinearLayout? = null
    private var coordsText: TextView? = null
    private var panelAtTop = false

    private var attached = false
    private var dismissed = false

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.currentWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(m)
            Pair(m.widthPixels, m.heightPixels)
        }
    }

    private fun buildViews() {
        val point = if (currentPointId in 1..10) {
            settingsRepo.getLatestSettings().getPointById(currentPointId)
        } else {
            null
        }
        val (sw, sh) = screenSize()

        val isBlue = currentPointId % 2 == 0 && currentPointId !in 1..10
        val marker = MarkerView(ctx, currentPointId, density, isBlue).apply {
            markerX = if (point?.isConfigured == true) point.x else sw / 2f
            markerY = if (point?.isConfigured == true) point.y else sh / 2f
        }
        markerView = marker

        val hint = TextView(ctx).apply {
            text = if (currentPointId in 1..10) {
                "Точка $currentPointId: коснись экрана или перетащи прицел"
            } else if (currentPointId % 2 == 1) {
                "Начало свайпа: коснись экрана или перетащи прицел"
            } else {
                "Конец свайпа: коснись экрана или перетащи прицел"
            }
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val coords = TextView(ctx).apply {
            setTextColor(Color.parseColor("#8CFF00"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(4))
        }
        coordsText = coords

        // Стеклянные кнопки в стиле .float-bar
        val cancelBtn = Button(ctx).apply {
            text = "Отмена"
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = roundBg(Color.parseColor("#44FF3333"), dp(12))
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(8) }
            setOnClickListener { dismiss() }
        }

        val saveBtn = Button(ctx).apply {
            text = "Сохранить"
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#6AD000"), Color.parseColor("#4A9A00"))
            ).apply {
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
            setOnClickListener { save() }
        }

        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            addView(cancelBtn)
            addView(saveBtn)
        }

        // Плавающая стеклянная панель .float-bar
        val panelLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                colors = intArrayOf(
                    Color.parseColor("#E6181B28"),
                    Color.parseColor("#FA0B0D18")
                )
                setStroke(dp(2), NeonTheme.getBorderColorInt())
            }
            isClickable = true
            addView(hint)
            addView(coords)
            addView(buttons)
        }
        panel = panelLayout

        val container = FrameLayout(ctx).apply {
            setBackgroundColor(Color.parseColor("#8005050A")) // Полупрозрачное затемнение
            addView(
                marker,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                panelLayout,
                FrameLayout.LayoutParams(
                    (minOf(sw, sh) * 0.85f).toInt().coerceAtMost(dp(360)),
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply {
                    bottomMargin = dp(32)
                    topMargin = dp(32)
                }
            )
        }
        root = container

        marker.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    marker.markerX = event.rawX
                    marker.markerY = event.rawY
                    marker.invalidate()
                    updateUi()
                    true
                }
                else -> true
            }
        }

        updateUi()
    }

    private fun updateUi() {
        val m = markerView ?: return
        coordsText?.text = "X=${m.markerX.toInt()}   Y=${m.markerY.toInt()}"

        // Панель уходит в противоположную от прицела половину экрана
        val (_, sh) = screenSize()
        val shouldBeAtTop = m.markerY > sh / 2f
        if (shouldBeAtTop != panelAtTop) {
            panelAtTop = shouldBeAtTop
            val p = panel ?: return
            val lp = p.layoutParams as? FrameLayout.LayoutParams ?: return
            lp.gravity = (if (shouldBeAtTop) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
            p.layoutParams = lp
        }
    }

    private fun save() {
        val m = markerView ?: return
        val (sw, sh) = screenSize()
        val orientation = if (sw > sh) 2 else 1 // 1 = Portrait, 2 = Landscape
        if (currentPointId in 1..10) {
            settingsRepo.updatePointCoordinates(
                pointId = currentPointId,
                x = m.markerX,
                y = m.markerY,
                orientation = orientation,
                screenWidth = sw,
                screenHeight = sh
            )
            EventLogManager.log(
                EventLogManager.TAG_OVERLAY,
                "SET POINT $currentPointId: (${m.markerX.toInt()}, ${m.markerY.toInt()})"
            )
        }
        onCoordinateCaptured?.invoke(currentPointId, m.markerX, m.markerY)
        dismiss()
    }

    fun show() {
        if (attached || dismissed) return
        buildViews()
        EventLogManager.log(EventLogManager.TAG_OVERLAY, "SET POINT $currentPointId START")

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
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

        try {
            windowManager.addView(root, params)
            attached = true
            activeOverlay = this
        } catch (e: Exception) {
            EventLogManager.log(
                EventLogManager.TAG_OVERLAY,
                "ERROR: не удалось открыть калибровку: ${e.message}",
                isError = true
            )
            dismiss()
        }
    }

    fun dismiss() {
        if (dismissed) return
        dismissed = true
        if (activeOverlay === this) {
            activeOverlay = null
        }
        root?.let {
            if (attached) {
                try {
                    windowManager.removeView(it)
                } catch (_: Exception) {
                }
            }
        }
        root = null
        markerView = null
        panel = null
        coordsText = null
        attached = false
        onDismissed?.invoke()
    }

    companion object {
        private var activeOverlay: CalibrationOverlayView? = null

        fun dismissActive() {
            activeOverlay?.dismiss()
            activeOverlay = null
        }
    }

    private fun roundBg(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    /** Прицел .aim-cross: перекрестие с неоновым свечением, кольцо и центральная точка. */
    private class MarkerView(
        context: Context,
        private val pointId: Int,
        private val density: Float,
        private val isBlue: Boolean
    ) : View(context) {

        var markerX: Float = 0f
        var markerY: Float = 0f

        private val mainColor = if (isBlue) Color.parseColor("#40B0FF") else Color.parseColor("#8CFF00")
        private val glowColor = if (isBlue) Color.parseColor("#6640B0FF") else Color.parseColor("#668CFF00")

        private val lineGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = glowColor
            strokeWidth = 6f * density
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = mainColor
            strokeWidth = 2f * density
        }
        private val ringGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = glowColor
            style = Paint.Style.STROKE
            strokeWidth = 8f * density
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = mainColor
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
        }
        private val dotGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = glowColor
            style = Paint.Style.FILL
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = mainColor
            style = Paint.Style.FILL
        }
        private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#D9080A14")
            style = Paint.Style.FILL
        }
        private val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = mainColor
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = mainColor
            textSize = 12f * density
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val cx = markerX - loc[0]
            val cy = markerY - loc[1]

            // 1. Линии перекрестия со свечением
            canvas.drawLine(0f, cy, width.toFloat(), cy, lineGlowPaint)
            canvas.drawLine(cx, 0f, cx, height.toFloat(), lineGlowPaint)
            canvas.drawLine(0f, cy, width.toFloat(), cy, linePaint)
            canvas.drawLine(cx, 0f, cx, height.toFloat(), linePaint)

            // 2. Кольцо прицела
            val ringRadius = 24f * density
            canvas.drawCircle(cx, cy, ringRadius, ringGlowPaint)
            canvas.drawCircle(cx, cy, ringRadius, ringPaint)

            // 3. Центральная точка
            canvas.drawCircle(cx, cy, 6f * density, dotGlowPaint)
            canvas.drawCircle(cx, cy, 3.5f * density, dotPaint)

            // 4. Бейдж с номером / названием
            val label = if (pointId in 1..10) "ТОЧКА $pointId" else if (pointId % 2 == 1) "НАЧАЛО" else "КОНЕЦ"
            val textWidth = textPaint.measureText(label)
            val badgeW = textWidth + 16f * density
            val badgeH = 22f * density
            val badgeX = cx - badgeW / 2f
            val badgeY = cy - ringRadius - badgeH - 6f * density

            val badgeRect = RectF(badgeX, badgeY, badgeX + badgeW, badgeY + badgeH)
            canvas.drawRoundRect(badgeRect, 8f * density, 8f * density, badgeBgPaint)
            canvas.drawRoundRect(badgeRect, 8f * density, 8f * density, badgeBorderPaint)
            canvas.drawText(label, cx, badgeY + badgeH - 6f * density, textPaint)
        }
    }
}
