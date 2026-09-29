package com.example.autoclicker.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.engine.GestureExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Полноэкранный прозрачный оверлей калибровки.
 *
 * Обеспечивает точный захват координат в той же системе отсчета,
 * которую использует AccessibilityService.dispatchGesture(),
 * с учетом ориентации экрана (Portrait/Landscape), статус-бара, навигационной панели
 * и вырезов экрана (Display Cutout).
 */
@SuppressLint("ViewConstructor")
class CalibrationOverlayView(
    context: Context,
    private val pointId: Int,
    private val onCoordinateCaptured: (Float, Float) -> Unit,
    private val onDismissed: (() -> Unit)? = null
) : FrameLayout(context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val settingsRepo = SettingsRepository.getInstance(context)
    private val gestureExecutor = GestureExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var screenWidth = 0
    private var screenHeight = 0
    private var recordedX: Float = -1f
    private var recordedY: Float = -1f

    private var windowParams: WindowManager.LayoutParams? = null

    // Элементы UI карточки управления
    private val hudCard: LinearLayout
    private val screenInfoText: TextView
    private val recordedInfoText: TextView
    private val testBtn: Button
    private val saveBtn: Button
    private val cancelBtn: Button

    // Внутренний холст для отрисовки прицела
    private val crosshairView: CrosshairCanvasView

    init {
        resolveScreenDimensions()

        // 1. Слой перекрестия и перехвата касаний
        crosshairView = CrosshairCanvasView(context)
        addView(
            crosshairView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        // 2. HUD-карточка с информацией и кнопками
        hudCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dpToPx(14)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE1A1D28")) // Тёмный полупрозрачный фон
                cornerRadius = dpToPx(16).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#44FFFFFF"))
            }
        }

        val titleText = TextView(context).apply {
            text = "КАЛИБРОВКА POINT $pointId"
            setTextColor(Color.parseColor("#FF2979FF"))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
        }
        hudCard.addView(titleText)

        val instructionText = TextView(context).apply {
            text = "Коснитесь нужной кнопки на игровом экране"
            setTextColor(Color.parseColor("#FFB0B3C6"))
            textSize = 12f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dpToPx(2), 0, dpToPx(8))
        }
        hudCard.addView(instructionText)

        // Блок Screen: W x H
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val orientationStr = if (isLandscape) "Landscape" else "Portrait"
        screenInfoText = TextView(context).apply {
            text = "Screen:\n$screenWidth x $screenHeight ($orientationStr)"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dpToPx(4))
        }
        hudCard.addView(screenInfoText)

        // Блок Recorded: X=..., Y=...
        recordedInfoText = TextView(context).apply {
            text = "Recorded:\nX = —\nY = —"
            setTextColor(Color.parseColor("#FFFFAB00"))
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dpToPx(10))
        }
        hudCard.addView(recordedInfoText)

        // Кнопки управления: TEST, СОХРАНИТЬ, ОТМЕНА
        val buttonsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        testBtn = Button(context).apply {
            text = "TEST"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            background = createButtonBg(Color.parseColor("#FF2979FF"), dpToPx(8))
            isEnabled = false
            alpha = 0.5f
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(38), 1f).apply {
                marginEnd = dpToPx(6)
            }
            setOnClickListener {
                performTestTap()
            }
        }

        saveBtn = Button(context).apply {
            text = "СОХРАНИТЬ"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            background = createButtonBg(Color.parseColor("#FF00C853"), dpToPx(8))
            isEnabled = false
            alpha = 0.5f
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(38), 1.3f).apply {
                marginEnd = dpToPx(6)
            }
            setOnClickListener {
                confirmAndSave()
            }
        }

        cancelBtn = Button(context).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 13f
            background = createButtonBg(Color.parseColor("#33F44336"), dpToPx(8))
            layoutParams = LinearLayout.LayoutParams(dpToPx(42), dpToPx(38))
            setOnClickListener {
                dismiss()
            }
        }

        buttonsRow.addView(testBtn)
        buttonsRow.addView(saveBtn)
        buttonsRow.addView(cancelBtn)
        hudCard.addView(buttonsRow)

        // Размещаем HUD вверху экрана с отступами
        val hudParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dpToPx(48)
        }
        addView(hudCard, hudParams)
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

    fun show() {
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        windowParams = params

        try {
            windowManager.addView(this, params)
            EventLogManager.log(
                EventLogManager.TAG_OVERLAY,
                "Режим калибровки открыт: экран $screenWidth x $screenHeight"
            )
        } catch (e: Exception) {
            EventLogManager.log(
                EventLogManager.TAG_OVERLAY,
                "Ошибка отображения калибровки: ${e.message}",
                isError = true
            )
        }
    }

    fun dismiss() {
        try {
            windowManager.removeView(this)
        } catch (e: Exception) {
            // Игнорируем если уже удален
        }
        onDismissed?.invoke()
    }

    private fun onUserTouchedCoordinate(x: Float, y: Float) {
        // Ограничиваем в пределах реального разрешения экрана
        recordedX = x.coerceIn(0f, screenWidth.toFloat())
        recordedY = y.coerceIn(0f, screenHeight.toFloat())

        recordedInfoText.text = "Recorded:\nX = ${recordedX.toInt()}\nY = ${recordedY.toInt()}"

        testBtn.isEnabled = true
        testBtn.alpha = 1.0f

        saveBtn.isEnabled = true
        saveBtn.alpha = 1.0f

        crosshairView.setTarget(recordedX, recordedY)

        EventLogManager.log(
            EventLogManager.TAG_OVERLAY,
            "Точка $pointId: выбрана позиция (${recordedX.toInt()}, ${recordedY.toInt()})"
        )
    }

    private fun performTestTap() {
        if (recordedX < 0f || recordedY < 0f) return

        val params = windowParams ?: return

        scope.launch {
            // Временно делаем окно сквозным (FLAG_NOT_TOUCHABLE),
            // чтобы жест от AccessibilityService без препятствий попал в PUBG Mobile под оверлеем
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            windowManager.updateViewLayout(this@CalibrationOverlayView, params)

            testBtn.text = "..."
            delay(60)

            val success = gestureExecutor.performTap(recordedX, recordedY)
            delay(150)

            // Восстанавливаем перехват касаний для возможности сохранения
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            windowManager.updateViewLayout(this@CalibrationOverlayView, params)
            testBtn.text = "TEST"

            if (success) {
                EventLogManager.log(
                    EventLogManager.TAG_GESTURE,
                    "TEST OK: жест выполнен по ($recordedX, $recordedY)"
                )
            }
        }
    }

    private fun confirmAndSave() {
        if (recordedX >= 0f && recordedY >= 0f) {
            settingsRepo.updatePoint(pointId, recordedX, recordedY)
            EventLogManager.log(
                EventLogManager.TAG_OVERLAY,
                "POINT $pointId успешно сохранён: X=${recordedX.toInt()}, Y=${recordedY.toInt()}"
            )
            onCoordinateCaptured(recordedX, recordedY)
            dismiss()
        }
    }

    private fun createButtonBg(color: Int, radiusPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx.toFloat()
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    /**
     * Внутренний View для отслеживания жестов и отрисовки перекрестия на экране.
     */
    private inner class CrosshairCanvasView(ctx: Context) : View(ctx) {

        private val dimPaint = Paint().apply {
            color = Color.parseColor("#40000000") // Лёгкое затемнение 25%, чтобы игра была отлично видна
        }

        private val crosshairOuterPaint = Paint().apply {
            color = Color.parseColor("#FFFF1744") // Ярко-красный
            strokeWidth = 3f
            isAntiAlias = true
            style = Paint.Style.STROKE
        }

        private val crosshairInnerPaint = Paint().apply {
            color = Color.parseColor("#FF00E676") // Ярко-зеленый центр
            strokeWidth = 4f
            isAntiAlias = true
            style = Paint.Style.FILL
        }

        private val linePaint = Paint().apply {
            color = Color.parseColor("#80FF1744")
            strokeWidth = 2f
            isAntiAlias = true
        }

        private var targetX = -1f
        private var targetY = -1f

        fun setTarget(x: Float, y: Float) {
            targetX = x
            targetY = y
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // Фон
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

            if (targetX >= 0f && targetY >= 0f) {
                // Направляющие линии по всей ширине и высоте экрана
                canvas.drawLine(0f, targetY, width.toFloat(), targetY, linePaint)
                canvas.drawLine(targetX, 0f, targetX, height.toFloat(), linePaint)

                // Круги прицела
                canvas.drawCircle(targetX, targetY, 36f, crosshairOuterPaint)
                canvas.drawCircle(targetX, targetY, 6f, crosshairInnerPaint)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    // MotionEvent.rawX / rawY дают точные абсолютные координаты дисплея
                    onUserTouchedCoordinate(event.rawX, event.rawY)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
