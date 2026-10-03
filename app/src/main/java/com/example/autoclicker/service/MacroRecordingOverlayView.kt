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
 * Визуально полностью прозрачен, видна только компактная плашка «🔴 Запись | Стоп».
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

    private var attached = false
    private var dismissed = false
    private val panelHitRect = Rect()

    private fun dp(value: Int): Int = (value * density).toInt()

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (attached && !dismissed) {
                val elapsedSec = macroRecorder.getElapsedTimeMs() / 1000
                val min = elapsedSec / 60
                val sec = elapsedSec % 60
                val timeStr = String.format(java.util.Locale.US, "%02d:%02d", min, sec)
                timerTextView?.text = "🔴 Запись $timeStr"

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

                    // Если касание попадает в плашку управления (кнопка Стоп),
                    // передаем событие панели и НЕ записываем его в макрос
                    if (panelHitRect.contains(rawX, rawY)) {
                        super.dispatchTouchEvent(ev)
                        return true
                    }
                }

                // Касание в любой другой области экрана записывается рекордером
                macroRecorder.processTouchEvent(ev)

                // ОБЯЗАТЕЛЬНО возвращаем true: касание полностью перехвачено,
                // нижележащее приложение не получает никаких событий!
                return true
            }
        }

        // Полностью прозрачный фон
        rootLayout.setBackgroundColor(Color.TRANSPARENT)
        rootLayout.isClickable = true
        rootLayout.isFocusable = false

        // Компактная плашка «🔴 Запись | Стоп»
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
        }
        controlPanel = panel

        val timerText = TextView(context).apply {
            text = "🔴 Запись 00:00"
            setTextColor(Color.parseColor("#FF5252"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        timerTextView = timerText
        panel.addView(timerText)

        val countText = TextView(context).apply {
            text = "0 ж."
            setTextColor(Color.parseColor("#80D8FF"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(dp(8), 0, dp(10), 0)
        }
        touchCountTextView = countText
        panel.addView(countText)

        // Кнопка «Стоп»
        val stopBtn = Button(context).apply {
            text = "⏹ СТОП"
            textSize = 11f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D32F2F"))
                cornerRadius = dp(16).toFloat()
            }
            val lp = LinearLayout.LayoutParams(dp(76), dp(32))
            layoutParams = lp
            setPadding(0, 0, 0, 0)
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

        try {
            windowManager.addView(root, params)
            attached = true
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
}
