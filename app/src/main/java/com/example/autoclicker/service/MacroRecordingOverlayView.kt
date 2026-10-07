package com.example.autoclicker.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.data.RecordedMacro
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.engine.MacroRecorder

/**
 * Полноэкранный прозрачный touchable Overlay для записи макроса.
 *
 * КРИТИЧЕСКИ ВАЖНО:
 * Полностью перехватывает все касания (MotionEvent) по всему экрану.
 * Нижележащие приложения НЕ получают эти касания и НЕ реагируют на движения пальцев.
 * Визуально полностью прозрачен, видна только компактная плашка «🔴 Запись | ⏸ | Стоп».
 *
 * Пауза: окно сжимается до плашки — можно открывать приложения и нажимать;
 * «Продолжить» снова разворачивает оверлей и дописывает жесты в тот же макрос
 * (время паузы в таймлайн макроса не попадает).
 */
@SuppressLint("ClickableViewAccessibility")
class MacroRecordingOverlayView(
    private val context: Context,
    private val onMacroRecorded: ((RecordedMacro) -> Unit)? = null,
    private val onDismissed: (() -> Unit)? = null
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val settingsRepo = SettingsRepository.getInstance(context)
    private val density = context.resources.displayMetrics.density
    private val macroRecorder = MacroRecorder()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var root: FrameLayout? = null
    private var controlPanel: LinearLayout? = null
    private var timerTextView: TextView? = null
    private var touchCountTextView: TextView? = null
    private var pauseBtn: Button? = null

    private var attached = false
    private var dismissed = false
    private var isPausedUi = false
    private var windowParams: WindowManager.LayoutParams? = null
    private val panelHitRect = Rect()

    private fun dp(value: Int): Int = (value * density).toInt()

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (attached && !dismissed) {
                val elapsedSec = macroRecorder.getElapsedTimeMs() / 1000
                val min = elapsedSec / 60
                val sec = elapsedSec % 60
                val timeStr = String.format(java.util.Locale.US, "%02d:%02d", min, sec)
                timerTextView?.text = if (isPausedUi || macroRecorder.isPaused()) {
                    "⏸ Пауза $timeStr"
                } else {
                    "🔴 Запись $timeStr"
                }

                val strokesCount = macroRecorder.getRecordedStrokesCount()
                touchCountTextView?.text = "$strokesCount ж."

                mainHandler.postDelayed(this, 500L)
            }
        }
    }

    private fun buildViews() {
        val rootLayout = object : FrameLayout(context) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                val panel = controlPanel
                if (panel != null && panel.visibility == View.VISIBLE) {
                    panel.getGlobalVisibleRect(panelHitRect)
                    val rawX = ev.rawX.toInt()
                    val rawY = ev.rawY.toInt()

                    // Плашка управления (Пауза / Стоп) — не пишем в макрос
                    if (panelHitRect.contains(rawX, rawY)) {
                        super.dispatchTouchEvent(ev)
                        return true
                    }
                }

                // На паузе окно сжато до плашки; на всякий случай не пишем
                if (isPausedUi || macroRecorder.isPaused()) {
                    return false
                }

                // Касание в любой другой области экрана записывается рекордером
                macroRecorder.processTouchEvent(ev)

                // ОБЯЗАТЕЛЬНО возвращаем true: касание полностью перехвачено
                return true
            }
        }

        rootLayout.setBackgroundColor(Color.TRANSPARENT)
        rootLayout.isClickable = true
        rootLayout.isFocusable = false

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE161B22"))
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.parseColor("#66FF5252"))
            }
            isClickable = true
            isFocusable = false
        }
        controlPanel = panel

        val timer = TextView(context).apply {
            text = "🔴 Запись 00:00"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, dp(8), 0)
        }
        timerTextView = timer
        panel.addView(timer)

        val count = TextView(context).apply {
            text = "0 ж."
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 12f
            setPadding(0, 0, dp(10), 0)
        }
        touchCountTextView = count
        panel.addView(count)

        // Кнопка «Пауза / Продолжить»
        val pauseButton = Button(context).apply {
            text = "⏸"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FF6D00"))
                cornerRadius = dp(16).toFloat()
            }
            minimumWidth = 0
            minimumHeight = 0
            setOnClickListener { togglePause() }
        }
        pauseBtn = pauseButton
        panel.addView(pauseButton)

        panel.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(6), 1)
        })

        // Кнопка «Стоп»
        val stopBtn = Button(context).apply {
            text = "Стоп"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(4), dp(14), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E53935"))
                cornerRadius = dp(16).toFloat()
            }
            minimumWidth = 0
            minimumHeight = 0
            setOnClickListener {
                finishAndSave()
            }
        }
        panel.addView(stopBtn)

        val panelParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply {
            topMargin = dp(44)
        }
        rootLayout.addView(panel, panelParams)

        root = rootLayout
    }

    private fun togglePause() {
        if (dismissed || !attached) return
        if (macroRecorder.isPaused()) {
            macroRecorder.resume()
            isPausedUi = false
            pauseBtn?.text = "⏸"
            applyFullscreenLayout()
            EventLogManager.log(EventLogManager.TAG_GESTURE, "MACRO RECORDING RESUMED")
            Toast.makeText(context, "Запись продолжена", Toast.LENGTH_SHORT).show()
        } else {
            macroRecorder.pause()
            isPausedUi = true
            pauseBtn?.text = "▶"
            applyPanelOnlyLayout()
            EventLogManager.log(EventLogManager.TAG_GESTURE, "MACRO RECORDING PAUSED")
            Toast.makeText(context, "Пауза: перейдите куда нужно, затем ▶", Toast.LENGTH_LONG).show()
        }
        val elapsedSec = macroRecorder.getElapsedTimeMs() / 1000
        val timeStr = String.format(java.util.Locale.US, "%02d:%02d", elapsedSec / 60, elapsedSec % 60)
        timerTextView?.text = if (isPausedUi) "⏸ Пауза $timeStr" else "🔴 Запись $timeStr"
    }

    private fun applyFullscreenLayout() {
        val params = windowParams ?: return
        val r = root ?: return
        val panel = controlPanel
        // Вернуть отступ плашки внутри полноэкранного окна
        if (panel != null) {
            val lp = panel.layoutParams as? FrameLayout.LayoutParams
            if (lp != null) {
                lp.topMargin = dp(44)
                lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                panel.layoutParams = lp
            }
        }
        params.width = WindowManager.LayoutParams.MATCH_PARENT
        params.height = WindowManager.LayoutParams.MATCH_PARENT
        params.x = 0
        params.y = 0
        params.flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        params.gravity = Gravity.TOP or Gravity.START
        try {
            windowManager.updateViewLayout(r, params)
            r.requestLayout()
        } catch (_: Exception) {
        }
    }

    private fun applyPanelOnlyLayout() {
        val params = windowParams ?: return
        val r = root ?: return
        val panel = controlPanel ?: return

        // Убрать внутренний margin — плашка заполняет маленькое окно
        val lp = panel.layoutParams as? FrameLayout.LayoutParams
        if (lp != null) {
            lp.topMargin = 0
            lp.gravity = Gravity.CENTER
            panel.layoutParams = lp
        }

        // Измерить плашку и задать точный размер окна (WRAP_CONTENT часто даёт 0)
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val w = (panel.measuredWidth + dp(12)).coerceAtLeast(dp(160))
        val h = (panel.measuredHeight + dp(12)).coerceAtLeast(dp(48))

        params.width = w
        params.height = h
        params.x = 0
        params.y = dp(44)
        // Без FLAG_NOT_TOUCHABLE — кнопки Пауза/Стоп должны получать касания
        params.flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        try {
            windowManager.updateViewLayout(r, params)
            r.requestLayout()
        } catch (e: Exception) {
            EventLogManager.log(
                EventLogManager.TAG_OVERLAY,
                "PAUSE layout error: ${e.message}",
                isError = true
            )
        }
    }

    private fun finishAndSave() {
        if (dismissed) return
        val macro = macroRecorder.finishRecording()
        settingsRepo.saveMacro(macro)

        EventLogManager.log(
            EventLogManager.TAG_GESTURE,
            "MACRO RECORDED: ${macro.strokes.size} жестов, ${macro.multitouchGroups.size} групп, ${macro.formattedDuration}"
        )

        Toast.makeText(
            context,
            "Макрос сохранён: ${macro.strokes.size} жестов (${macro.formattedDuration})",
            Toast.LENGTH_LONG
        ).show()

        onMacroRecorded?.invoke(macro)
        dismiss()
    }

    fun show() {
        if (attached || dismissed) return
        buildViews()

        macroRecorder.start()
        EventLogManager.log(EventLogManager.TAG_GESTURE, "MACRO RECORDING STARTED: перехват касаний активен")

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
        windowParams = params

        try {
            windowManager.addView(root, params)
            attached = true
            isPausedUi = false
            activeOverlay = this
            mainHandler.post(timerRunnable)
        } catch (e: Exception) {
            EventLogManager.log(
                EventLogManager.TAG_OVERLAY,
                "ERROR: не удалось открыть оверлей записи макроса: ${e.message}",
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
        mainHandler.removeCallbacks(timerRunnable)

        root?.let {
            if (attached) {
                try {
                    windowManager.removeView(it)
                } catch (e: Exception) {
                    // Ignore
                }
                attached = false
            }
        }
        onDismissed?.invoke()
    }

    companion object {
        private var activeOverlay: MacroRecordingOverlayView? = null

        fun dismissActive() {
            activeOverlay?.dismiss()
            activeOverlay = null
        }

        fun isActive(): Boolean = activeOverlay != null
    }
}
