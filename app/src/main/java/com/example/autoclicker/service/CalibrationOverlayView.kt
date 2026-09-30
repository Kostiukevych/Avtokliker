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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.engine.GestureExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CalibrationMarkerWindow(
    private val context: Context,
    private val pointId: Int,
    private val onCoordinatesUpdated: (Float, Float) -> Unit,
    private val onDismissed: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val reticleView = ReticleView(context, pointId)
    private var windowParams: WindowManager.LayoutParams? = null
    private var screenWidth = 1080
    private var screenHeight = 1920
    
    private val reticleSizePx = dpToPx(56)
    private var currentTargetX = 0f
    private var currentTargetY = 0f

    init {
        resolveScreenDimensions()
        setupDragListener()
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
    private fun setupDragListener() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        reticleView.setOnTouchListener { _, event ->
            val params = windowParams ?: return@setOnTouchListener false
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

                    params.x = (initialX + dx).coerceIn(0, screenWidth - reticleSizePx)
                    params.y = (initialY + dy).coerceIn(0, screenHeight - reticleSizePx)

                    try {
                        windowManager.updateViewLayout(reticleView, params)
                    } catch (_: Exception) {}

                    updateCoordinates()
                    true
                }
                else -> false
            }
        }
    }

    private fun updateCoordinates() {
        val loc = IntArray(2)
        reticleView.getLocationOnScreen(loc)
        currentTargetX = (loc[0] + reticleSizePx / 2f).coerceIn(0f, screenWidth.toFloat())
        currentTargetY = (loc[1] + reticleSizePx / 2f).coerceIn(0f, screenHeight.toFloat())
        onCoordinatesUpdated(currentTargetX, currentTargetY)
    }

    fun show() {
        val layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val params = WindowManager.LayoutParams(
            reticleSizePx,
            reticleSizePx,
            layoutFlag,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth / 2 - reticleSizePx / 2
            y = screenHeight / 2 - reticleSizePx / 2
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        windowParams = params

        try {
            windowManager.addView(reticleView, params)
            updateCoordinates()
        } catch (e: Exception) {
            EventLogManager.log(
                EventLogManager.TAG_OVERLAY,
                "ERROR: Маркер P$pointId не отобразился: ${e.message}",
                isError = true
            )
        }
    }

    fun dismiss() {
        try {
            windowManager.removeView(reticleView)
        } catch (_: Exception) {}
        onDismissed()
    }

    fun getCoordinates(): Pair<Float, Float> = Pair(currentTargetX, currentTargetY)

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    private class ReticleView(context: Context, private val pointId: Int) : View(context) {
        private val color = when (pointId) {
            1 -> Color.parseColor("#00E5FF")
            2 -> Color.parseColor("#FFAB00")
            3 -> Color.parseColor("#00E676")
            else -> Color.CYAN
        }

        private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = this@ReticleView.color
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(2f)
        }

        private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = this@ReticleView.color
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(2f)
        }

        private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.FILL
        }

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dpToPx(10f)
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = width / 2f - dpToPx(4f)

            canvas.drawCircle(cx, cy, radius, circlePaint)
            val gap = radius * 0.25f
            canvas.drawLine(cx - radius, cy, cx - gap, cy, crosshairPaint)
            canvas.drawLine(cx + gap, cy, cx + radius, cy, crosshairPaint)
            canvas.drawLine(cx, cy - radius, cx, cy - gap, crosshairPaint)
            canvas.drawLine(cx, cy + gap, cx, cy + radius, crosshairPaint)

            canvas.drawCircle(cx, cy, dpToPx(3f), centerDotPaint)
            canvas.drawText("P$pointId", cx, cy + radius + dpToPx(16f), textPaint)
        }

        private fun dpToPx(dp: Float): Float {
            return dp * resources.displayMetrics.density
        }
    }
}

class CalibrationControlPanel(
    private val context: Context,
    private val pointId: Int,
    private val settingsRepo: SettingsRepository,
    private val getCoordinates: () -> Pair<Float, Float>,
    private val onDismissed: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val panelView = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = FrameLayout.LayoutParams(dpToPx(300), FrameLayout.LayoutParams.WRAP_CONTENT)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(12).toFloat()
            setColor(Color.parseColor("#E6121722"))
            setStroke(dpToPx(1), Color.parseColor("#443A4560"))
        }
        setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
    }

    private val titleText = TextView(context).apply {
        text = "Установка Point $pointId"
        setTextColor(Color.WHITE)
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, dpToPx(6)) }
        maxLines = 1
    }

    private val coordText = TextView(context).apply {
        text = "X: 0   Y: 0"
        setTextColor(Color.parseColor("#FFD54F"))
        textSize = 13f
        typeface = Typeface.MONOSPACE
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, dpToPx(8)) }
        maxLines = 1
    }

    private val buttonRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private val saveBtn = Button(context).apply {
        text = "СОХРАНИТЬ"
        setTextColor(Color.WHITE)
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        background = createButtonBg(Color.parseColor("#00C853"), dpToPx(6))
        layoutParams = LinearLayout.LayoutParams(0, dpToPx(48), 1f).apply {
            marginEnd = dpToPx(4)
        }
        maxLines = 1
        setOnClickListener {
            val (x, y) = getCoordinates()
            settingsRepo.updatePointCoordinates(pointId, x, y)
            EventLogManager.log(
                EventLogManager.TAG_OVERLAY,
                "SET POINT $pointId SAVED X=${x.toInt()} Y=${y.toInt()}"
            )
            dismiss()
        }
    }

    private val cancelBtn = Button(context).apply {
        text = "ОТМЕНА"
        setTextColor(Color.WHITE)
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        background = createButtonBg(Color.parseColor("#757575"), dpToPx(6))
        layoutParams = LinearLayout.LayoutParams(0, dpToPx(48), 1f).apply {
            marginStart = dpToPx(4)
        }
        maxLines = 1
        setOnClickListener {
            dismiss()
        }
    }

    private var windowParams: WindowManager.LayoutParams? = null

    init {
        buttonRow.addView(saveBtn)
        buttonRow.addView(cancelBtn)
        panelView.addView(titleText)
        panelView.addView(coordText)
        panelView.addView(buttonRow)
    }

    fun show() {
        val layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val params = WindowManager.LayoutParams(
            dpToPx(300),
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dpToPx(24)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        windowParams = params

        try {
            windowManager.addView(panelView, params)
            startCoordinateUpdateLoop()
        } catch (e: Exception) {
            EventLogManager.log(
                EventLogManager.TAG_OVERLAY,
                "ERROR: Панель P$pointId не отобразилась: ${e.message}",
                isError = true
            )
        }
    }

    private fun startCoordinateUpdateLoop() {
        scope.launch {
            while (true) {
                val (x, y) = getCoordinates()
                coordText.text = "X: ${x.toInt()}   Y: ${y.toInt()}"
                delay(100)
            }
        }
    }

    fun dismiss() {
        scope.cancel()
        try {
            windowManager.removeView(panelView)
        } catch (_: Exception) {}
        onDismissed()
    }

    private fun createButtonBg(color: Int, radiusPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(color)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}

class CalibrationOverlayView(
    context: Context,
    private val pointId: Int,
    private val onDismissed: (() -> Unit)? = null
) {
    private val markerWindow: CalibrationMarkerWindow
    private val controlPanel: CalibrationControlPanel
    private val settingsRepo = SettingsRepository.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        EventLogManager.log(EventLogManager.TAG_OVERLAY, "SET POINT $pointId START")

        markerWindow = CalibrationMarkerWindow(
            context = context,
            pointId = pointId,
            onCoordinatesUpdated = { _, _ -> },
            onDismissed = { dismiss() }
        )

        controlPanel = CalibrationControlPanel(
            context = context,
            pointId = pointId,
            settingsRepo = settingsRepo,
            getCoordinates = { markerWindow.getCoordinates() },
            onDismissed = { dismiss() }
        )
    }

    fun show() {
        markerWindow.show()
        controlPanel.show()
        
        scope.launch {
            delay(90_000L)
            dismiss()
        }
    }

    fun dismiss() {
        scope.cancel()
        markerWindow.dismiss()
        controlPanel.dismiss()
        onDismissed?.invoke()
    }
}
