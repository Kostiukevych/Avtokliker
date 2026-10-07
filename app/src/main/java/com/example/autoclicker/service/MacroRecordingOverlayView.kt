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
 * Запись макроса:
 * 1) Полноэкранный слой — перехват касаний (убирается на паузе).
 * 2) Отдельная плашка-окно с кнопками — всегда поверх и всегда кликабельна.
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

    /** Полноэкранный перехват касаний */
    private var captureRoot: FrameLayout? = null
    private var captureParams: WindowManager.LayoutParams? = null
    private var captureAttached = false

    /** Плашка управления — отдельное окно */
    private var panelRoot: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelAttached = false

    private var timerTextView: TextView? = null
    private var touchCountTextView: TextView? = null
    private var pauseBtn: Button? = null

    private var attached = false
    private var dismissed = false
    private var isPausedUi = false

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
                touchCountTextView?.text = "${macroRecorder.getRecordedStrokesCount()} ж."
                mainHandler.postDelayed(this, 500L)
            }
        }
    }

    private fun baseOverlayFlags(): Int {
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    }

    private fun applyCutout(params: WindowManager.LayoutParams) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildCaptureLayer() {
        val root = object : FrameLayout(context) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (isPausedUi || macroRecorder.isPaused()) {
                    // На паузе слой не должен быть в окне; на всякий случай
                    return false
                }
                macroRecorder.processTouchEvent(ev)
                return true
            }
        }
        root.setBackgroundColor(Color.TRANSPARENT)
        root.isClickable = true
        captureRoot = root
    }

    private fun buildPanel() {
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
            elevation = dp(8).toFloat()
        }

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

        val pauseButton = Button(context).apply {
            text = "⏸"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FF6D00"))
                cornerRadius = dp(16).toFloat()
            }
            minimumWidth = 0
            minimumHeight = 0
            isClickable = true
            isEnabled = true
            setOnClickListener {
                EventLogManager.log(EventLogManager.TAG_GESTURE, "MACRO: pause button clicked")
                togglePause()
            }
        }
        pauseBtn = pauseButton
        panel.addView(pauseButton)

        panel.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(6), 1)
        })

        val stopBtn = Button(context).apply {
            text = "Стоп"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(6), dp(16), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E53935"))
                cornerRadius = dp(16).toFloat()
            }
            minimumWidth = 0
            minimumHeight = 0
            isClickable = true
            isEnabled = true
            setOnClickListener {
                EventLogManager.log(EventLogManager.TAG_GESTURE, "MACRO: stop button clicked")
                finishAndSave()
            }
        }
        panel.addView(stopBtn)

        panelRoot = panel
    }

    private fun attachPanel() {
        if (panelAttached || panelRoot == null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseOverlayFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(44)
            applyCutout(this)
        }
        panelParams = params
        try {
            windowManager.addView(panelRoot, params)
            panelAttached = true
        } catch (e: Exception) {
            EventLogManager.log(
                EventLogManager.TAG_OVERLAY,
                "ERROR: panel addView: ${e.message}",
                isError = true
            )
        }
    }

    private fun attachCapture() {
        if (captureAttached || captureRoot == null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseOverlayFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            applyCutout(this)
        }
        captureParams = params
        try {
            // Сначала capture, потом panel поверх — panel добавляем отдельно после
            windowManager.addView(captureRoot, params)
            captureAttached = true
        } catch (e: Exception) {
            EventLogManager.log(
                EventLogManager.TAG_OVERLAY,
                "ERROR: capture addView: ${e.message}",
                isError = true
            )
        }
    }

    private fun detachCapture() {
        if (!captureAttached) return
        try {
            captureRoot?.let { windowManager.removeView(it) }
        } catch (_: Exception) {
        }
        captureAttached = false
    }

    private fun togglePause() {
        if (dismissed || !attached) return
        if (macroRecorder.isPaused()) {
            // Продолжить
            macroRecorder.resume()
            isPausedUi = false
            pauseBtn?.text = "⏸"
            attachCapture()
            // Плашку снова поверх capture
            if (panelAttached && panelRoot != null && panelParams != null) {
                try {
                    windowManager.removeView(panelRoot)
                    windowManager.addView(panelRoot, panelParams)
                } catch (_: Exception) {
                    try {
                        windowManager.updateViewLayout(panelRoot, panelParams)
                    } catch (_: Exception) {
                    }
                }
            }
            EventLogManager.log(EventLogManager.TAG_GESTURE, "MACRO RECORDING RESUMED")
            Toast.makeText(context, "Запись продолжена", Toast.LENGTH_SHORT).show()
        } else {
            // Пауза: убираем полноэкранный перехват — экран свободен
            macroRecorder.pause()
            isPausedUi = true
            pauseBtn?.text = "▶"
            detachCapture()
            EventLogManager.log(EventLogManager.TAG_GESTURE, "MACRO RECORDING PAUSED")
            Toast.makeText(context, "Пауза: перейдите куда нужно, затем ▶", Toast.LENGTH_LONG).show()
        }
        val elapsedSec = macroRecorder.getElapsedTimeMs() / 1000
        val timeStr = String.format(java.util.Locale.US, "%02d:%02d", elapsedSec / 60, elapsedSec % 60)
        timerTextView?.text = if (isPausedUi) "⏸ Пауза $timeStr" else "🔴 Запись $timeStr"
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
        buildCaptureLayer()
        buildPanel()

        macroRecorder.start()
        EventLogManager.log(EventLogManager.TAG_GESTURE, "MACRO RECORDING STARTED")

        try {
            attachCapture()
            attachPanel()
            attached = true
            isPausedUi = false
            activeOverlay = this
            mainHandler.post(timerRunnable)
        } catch (e: Exception) {
            EventLogManager.log(
                EventLogManager.TAG_OVERLAY,
                "ERROR: не удалось открыть оверлей записи: ${e.message}",
                isError = true
            )
            dismiss()
        }
    }

    fun dismiss() {
        if (dismissed) return
        dismissed = true
        if (activeOverlay === this) activeOverlay = null
        mainHandler.removeCallbacks(timerRunnable)

        detachCapture()
        if (panelAttached) {
            try {
                panelRoot?.let { windowManager.removeView(it) }
            } catch (_: Exception) {
            }
            panelAttached = false
        }
        attached = false
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
