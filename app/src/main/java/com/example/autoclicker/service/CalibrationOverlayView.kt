package com.example.autoclicker.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.engine.CycleController
import com.example.autoclicker.engine.GestureExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Перемещаемый маркер калибровки (Movable Calibration Overlay).
 *
 * Отображает перетаскиваемый прицел и компактную плашку управления поверх игры.
 * Позволяет точно навести прицел на игровую кнопку, протестировать клик и сохранить координаты.
 * Поддерживает быстрое переключение между Point 1, Point 2 и Point 3 на лету.
 */
@SuppressLint("ViewConstructor")
class CalibrationOverlayView(
    context: Context,
    private var currentPointId: Int,
    private val onCoordinateCaptured: (Int, Float, Float) -> Unit,
    private val onDismissed: (() -> Unit)? = null
) : LinearLayout(context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val settingsRepo = SettingsRepository.getInstance(context)
    private val gestureExecutor = GestureExecutor()
    private val cycleController = CycleController.getInstance(gestureExecutor, settingsRepo)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var screenWidth = 1080
    private var screenHeight = 1920

    private var windowParams: WindowManager.LayoutParams? = null

    // UI элементы плашки
    private val hudCard: LinearLayout
    private val titleText: TextView
    private val coordText: TextView
    private val saveBtn: Button
    private val testBtn: Button
    private val cancelBtn: Button
    private val p1Btn: Button
    private val p2Btn: Button
    private val p3Btn: Button
    private val reticleView: ReticleView

    private val reticleSizePx = dpToPx(56)

    // Текущие экранные координаты точки центра прицела
    private var currentTargetX: Float = 0f
    private var currentTargetY: Float = 0f

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        resolveScreenDimensions()

        // 1. Компактная карточка управления над прицелом
        hudCard = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val padH = dpToPx(12)
            val padV = dpToPx(8)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6121722"))
                cornerRadius = dpToPx(12).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#443A4560"))
            }
        }

        // Ряд кнопок переключения между точками P1, P2, P3
        val pointSelectorRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dpToPx(4))
        }

        p1Btn = createPointSelectorBtn(1, "P1", Color.parseColor("#00E5FF"))
        p2Btn = createPointSelectorBtn(2, "P2", Color.parseColor("#FFAB00"))
        p3Btn = createPointSelectorBtn(3, "P3", Color.parseColor("#00E676"))

        pointSelectorRow.addView(p1Btn)
        pointSelectorRow.addView(p2Btn)
        pointSelectorRow.addView(p3Btn)
        hudCard.addView(pointSelectorRow)

        titleText = TextView(context).apply {
            text = "УСТАНОВКА P$currentPointId"
            setTextColor(getPointColor(currentPointId))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
        }
        hudCard.addView(titleText)

        coordText = TextView(context).apply {
            text = "X: --   Y: --"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dpToPx(2), 0, dpToPx(6))
        }
        hudCard.addView(coordText)

        // Кнопки: Сохранить, Тест, Отмена
        val buttonsRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        saveBtn = Button(context).apply {
            text = "Сохранить"
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            background = createButtonBg(Color.parseColor("#00C853"), dpToPx(6))
            val lp = LayoutParams(LayoutParams.WRAP_CONTENT, dpToPx(32)).apply {
                marginEnd = dpToPx(4)
            }
            layoutParams = lp
            setPadding(dpToPx(10), 0, dpToPx(10), 0)
            setOnClickListener {
                confirmAndSave()
            }
        }

        testBtn = Button(context).apply {
            text = "Тест"
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            background = createButtonBg(Color.parseColor("#2979FF"), dpToPx(6))
            val lp = LayoutParams(LayoutParams.WRAP_CONTENT, dpToPx(32)).apply {
                marginEnd = dpToPx(4)
            }
            layoutParams = lp
            setPadding(dpToPx(10), 0, dpToPx(10), 0)
            setOnClickListener {
                performTestTap()
            }
        }

        cancelBtn = Button(context).apply {
            text = "✕"
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 12f
            background = createButtonBg(Color.parseColor("#263238"), dpToPx(6))
            val lp = LayoutParams(dpToPx(32), dpToPx(32))
            layoutParams = lp
            setOnClickListener {
                dismiss()
            }
        }

        buttonsRow.addView(saveBtn)
        buttonsRow.addView(testBtn)
        buttonsRow.addView(cancelBtn)
        hudCard.addView(buttonsRow)

        // 2. Прицел (Reticle View)
        reticleView = ReticleView(context)
        val reticleLp = LayoutParams(reticleSizePx, reticleSizePx).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dpToPx(4)
        }

        addView(hudCard)
        addView(reticleView, reticleLp)

        updatePointSelectorUi()
        setupDragTouchListener()
    }

    private fun getPointColor(pointId: Int): Int {
        return when (pointId) {
            1 -> Color.parseColor("#00E5FF")
            2 -> Color.parseColor("#FFAB00")
            3 -> Color.parseColor("#00E676")
            else -> Color.CYAN
        }
    }

    private fun createPointSelectorBtn(pointId: Int, label: String, color: Int): Button {
        return Button(context).apply {
            text = label
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            val lp = LayoutParams(dpToPx(36), dpToPx(28)).apply {
                marginEnd = dpToPx(4)
            }
            layoutParams = lp
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                switchPoint(pointId)
            }
        }
    }

    private fun switchPoint(newPointId: Int) {
        currentPointId = newPointId
        titleText.text = "УСТАНОВКА P$currentPointId"
        titleText.setTextColor(getPointColor(currentPointId))
        reticleView.setPointColor(getPointColor(currentPointId))
        updatePointSelectorUi()

        // Перемещаем прицел к текущим координатам выбранной точки, если она уже настроена
        val savedPoint = settingsRepo.getLatestSettings().getPointById(currentPointId)
        if (savedPoint.isConfigured) {
            val params = windowParams ?: return
            params.x = (savedPoint.x - width / 2f).toInt().coerceIn(0, screenWidth - 100)
            params.y = (savedPoint.y - dpToPx(80)).toInt().coerceIn(0, screenHeight - 100)
            try {
                windowManager.updateViewLayout(this, params)
            } catch (_: Exception) {}
            post {
                updateCoordinatesFromLayout()
            }
        }
    }

    private fun updatePointSelectorUi() {
        val activeColor = getPointColor(currentPointId)
        val inactiveBg = Color.parseColor("#1C2633")

        p1Btn.apply {
            setTextColor(if (currentPointId == 1) Color.BLACK else Color.WHITE)
            background = createButtonBg(if (currentPointId == 1) activeColor else inactiveBg, dpToPx(6))
        }
        p2Btn.apply {
            setTextColor(if (currentPointId == 2) Color.BLACK else Color.WHITE)
            background = createButtonBg(if (currentPointId == 2) activeColor else inactiveBg, dpToPx(6))
        }
        p3Btn.apply {
            setTextColor(if (currentPointId == 3) Color.BLACK else Color.WHITE)
            background = createButtonBg(if (currentPointId == 3) activeColor else inactiveBg, dpToPx(6))
        }
    }

    private fun resolveScreenDimensions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragTouchListener() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        val dragListener = OnTouchListener { _, event ->
            val params = windowParams ?: return@OnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    params.x = (initialX + dx).coerceIn(0, screenWidth - width.coerceAtLeast(100))
                    params.y = (initialY + dy).coerceIn(0, screenHeight - height.coerceAtLeast(100))

                    try {
                        windowManager.updateViewLayout(this, params)
                    } catch (_: Exception) {}

                    updateCoordinatesFromLayout()
                    true
                }
                else -> false
            }
        }

        reticleView.setOnTouchListener(dragListener)
        hudCard.setOnTouchListener(dragListener)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        resolveScreenDimensions()
        post {
            updateCoordinatesFromLayout()
        }
    }

    private fun updateCoordinatesFromLayout() {
        val loc = IntArray(2)
        reticleView.getLocationOnScreen(loc)
        val rw = reticleView.width
        val rh = reticleView.height

        if (rw > 0 && rh > 0 && (loc[0] != 0 || loc[1] != 0)) {
            currentTargetX = loc[0] + rw / 2f
            currentTargetY = loc[1] + rh / 2f
        } else {
            val params = windowParams
            if (params != null) {
                currentTargetX = params.x + width / 2f
                currentTargetY = (params.y + hudCard.height + reticleSizePx / 2f)
            }
        }

        currentTargetX = currentTargetX.coerceIn(0f, screenWidth.toFloat())
        currentTargetY = currentTargetY.coerceIn(0f, screenHeight.toFloat())

        coordText.text = "X: ${currentTargetX.toInt()}   Y: ${currentTargetY.toInt()}"
    }

    fun show() {
        val layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val savedPoint = settingsRepo.getLatestSettings().getPointById(currentPointId)
        val startX = if (savedPoint.isConfigured) {
            (savedPoint.x - reticleSizePx / 2f).toInt()
        } else {
            screenWidth / 2 - dpToPx(80)
        }
        val startY = if (savedPoint.isConfigured) {
            (savedPoint.y - dpToPx(80)).toInt()
        } else {
            screenHeight / 3
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX.coerceIn(0, screenWidth - dpToPx(160))
            y = startY.coerceIn(0, screenHeight - dpToPx(160))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        windowParams = params

        try {
            windowManager.addView(this, params)
        } catch (e: Exception) {
            EventLogManager.log(
                EventLogManager.TAG_OVERLAY,
                "ERROR: Не удалось показать прицел: ${e.message}",
                isError = true
            )
            return
        }

        post {
            updateCoordinatesFromLayout()
        }
    }

    fun dismiss() {
        try {
            windowManager.removeView(this)
        } catch (_: Exception) {}
        onDismissed?.invoke()
    }

    private fun confirmAndSave() {
        val orientation = context.resources.configuration.orientation
        settingsRepo.updatePointCoordinates(
            pointId = currentPointId,
            x = currentTargetX,
            y = currentTargetY,
            orientation = orientation,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )

        EventLogManager.log(
            EventLogManager.TAG_GESTURE,
            "Point $currentPointId записана: [${currentTargetX.toInt()}, ${currentTargetY.toInt()}]"
        )
        Toast.makeText(
            context,
            "P$currentPointId сохранена: (${currentTargetX.toInt()}, ${currentTargetY.toInt()})",
            Toast.LENGTH_SHORT
        ).show()

        onCoordinateCaptured(currentPointId, currentTargetX, currentTargetY)
        dismiss()
    }

    private fun performTestTap() {
        updateCoordinatesFromLayout()
        testBtn.isEnabled = false
        testBtn.text = "•"

        scope.launch {
            // Тестовый клик по текущему положению прицела
            val params = windowParams
            if (params != null) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                try {
                    windowManager.updateViewLayout(this@CalibrationOverlayView, params)
                } catch (_: Exception) {}
            }

            delay(60)
            val success = gestureExecutor.performTap(currentTargetX, currentTargetY, duration = 80L)
            delay(100)

            if (params != null) {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                try {
                    windowManager.updateViewLayout(this@CalibrationOverlayView, params)
                } catch (_: Exception) {}
            }

            testBtn.text = "Тест"
            testBtn.isEnabled = true

            if (success) {
                EventLogManager.log(
                    EventLogManager.TAG_GESTURE,
                    "P$currentPointId ТЕСТ [${currentTargetX.toInt()}, ${currentTargetY.toInt()}] OK"
                )
            } else {
                EventLogManager.log(
                    EventLogManager.TAG_GESTURE,
                    "ERROR: Тестовый жест не выполнен",
                    isError = true
                )
            }
        }
    }

    private fun createButtonBg(color: Int, radiusPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(color)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    /**
     * Графический виджет прицела с центром и делениями.
     */
    private class ReticleView(context: Context) : View(context) {

        private var pointColor = Color.parseColor("#00E5FF")

        private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pointColor
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(2f)
        }

        private val outerCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pointColor
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(1f)
            alpha = 130
        }

        private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pointColor
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(2f)
        }

        private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.FILL
        }

        fun setPointColor(color: Int) {
            pointColor = color
            circlePaint.color = color
            outerCirclePaint.color = color
            crosshairPaint.color = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = width / 2f - dpToPx(4f)

            // Внешнее и внутреннее кольцо прицела
            canvas.drawCircle(cx, cy, radius, outerCirclePaint)
            canvas.drawCircle(cx, cy, radius * 0.55f, circlePaint)

            // Перекрестие
            val gap = radius * 0.25f
            canvas.drawLine(cx - radius, cy, cx - gap, cy, crosshairPaint)
            canvas.drawLine(cx + gap, cy, cx + radius, cy, crosshairPaint)
            canvas.drawLine(cx, cy - radius, cx, cy - gap, crosshairPaint)
            canvas.drawLine(cx, cy + gap, cx, cy + radius, crosshairPaint)

            // Центральная точка клика
            canvas.drawCircle(cx, cy, dpToPx(3f), centerDotPaint)
        }

        private fun dpToPx(dp: Float): Float {
            return dp * resources.displayMetrics.density
        }
    }
}
