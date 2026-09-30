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

/**
 * Полноэкранный оверлей калибровки точки.
 * Тапни / перетащи по экрану, чтобы поставить прицел, затем нажми «Сохранить».
 * Координаты сохраняются в абсолютных пикселях экрана.
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
        val point = settingsRepo.getLatestSettings().let {
            when (currentPointId) {
                1 -> it.point1
                2 -> it.point2
                else -> it.point3
            }
        }
        val (sw, sh) = screenSize()

        val marker = MarkerView(ctx, currentPointId, density).apply {
            markerX = if (point.isConfigured) point.x else sw / 2f
            markerY = if (point.isConfigured) point.y else sh / 2f
        }
        markerView = marker

        val hint = TextView(ctx).apply {
            text = "Точка $currentPointId: коснись экрана или перетащи прицел"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val coords = TextView(ctx).apply {
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        coordsText = coords

        val saveBtn = Button(ctx).apply {
            text = "Сохранить"
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = roundBg(Color.parseColor("#2E7D32"))
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) }
            setOnClickListener { save() }
        }

        val cancelBtn = Button(ctx).apply {
            text = "Отмена"
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = roundBg(Color.parseColor("#C62828"))
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
            setOnClickListener { dismiss() }
        }

        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            addView(saveBtn)
            addView(cancelBtn)
        }

        val panelLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F0141E2B"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#4D90CAF9"))
            }
            isClickable = true
            addView(hint)
            addView(coords)
            addView(buttons)
        }
        panel = panelLayout

        val container = FrameLayout(ctx).apply {
            setBackgroundColor(Color.parseColor("#66000000"))
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
                    (minOf(sw, sh) * 0.85f).toInt(),
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
        coordsText?.text = "X=${m.markerX.toInt()}  Y=${m.markerY.toInt()}"

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

    private fun roundBg(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(8).toFloat()
    }

    /** Прицел: круг + перекрестие на всю ширину/высоту экрана. */
    private class MarkerView(
        context: Context,
        private val pointId: Int,
        private val density: Float
    ) : View(context) {

        var markerX: Float = 0f
        var markerY: Float = 0f

        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#9900E5FF")
            strokeWidth = 1.5f * density
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF00E5FF")
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFF1744")
            style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 14f * density
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // markerX/Y — экранные координаты; переводим в координаты этого view
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val cx = markerX - loc[0]
            val cy = markerY - loc[1]

            canvas.drawLine(0f, cy, width.toFloat(), cy, linePaint)
            canvas.drawLine(cx, 0f, cx, height.toFloat(), linePaint)
            canvas.drawCircle(cx, cy, 24f * density, ringPaint)
            canvas.drawCircle(cx, cy, 4f * density, dotPaint)
            canvas.drawText("P$pointId", cx, cy - 32f * density, textPaint)
        }
    }
}
