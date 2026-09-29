package com.example.autoclicker.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.autoclicker.MainActivity
import com.example.autoclicker.data.ClickerSettings
import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.engine.CycleController
import com.example.autoclicker.engine.CycleStatus
import com.example.autoclicker.engine.GestureExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Плавающий оверлей для работы поверх игр (включая PUBG Mobile).
 *
 * Режимы:
 * 1. Свернутый (MINIMIZED) — компактная полупрозрачная плавающая кнопка [ ▶ ] / [ ■ ].
 *    Перемещается пальцем, помнит координаты, клик разворачивает меню.
 * 2. Развернутый (EXPANDED) — компактная панель:
 *    - AUTO CLICKER
 *    - STATUS: RUNNING / STOPPED
 *    - NEXT ACTION: ...
 *    - TIMER: MM:SS
 *    - Кнопки [ START ], [ STOP ]
 *    - Кнопка [ SETTINGS ] (открывает настройки)
 *    - Кнопка [ MINIMIZE ] (сворачивает обратно в кнопку)
 *    - Быстрые кнопки записи и тестирования точек REC 1..3 и TEST 1..3
 */
class FloatingOverlayService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var windowManager: WindowManager
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var cycleController: CycleController

    private var overlayParams: WindowManager.LayoutParams? = null
    private var rootContainer: FrameLayout? = null

    // Развернутая панель
    private lateinit var expandedLayout: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var nextActionText: TextView
    private lateinit var timerText: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var settingsBtn: Button
    private lateinit var minimizeBtn: Button

    // Свернутая плавающая кнопка
    private lateinit var floatingBadgeButton: Button

    private var isMinimized = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        settingsRepo = SettingsRepository.getInstance(applicationContext)
        val gestureExecutor = GestureExecutor()
        cycleController = CycleController.getInstance(gestureExecutor, settingsRepo)

        createOverlay()
        observeControllerState()
        EventLogManager.log(EventLogManager.TAG_OVERLAY, "FloatingOverlayService запущен")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AutoClickForegroundService.start(applicationContext)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
        serviceScope.cancel()
        EventLogManager.log(EventLogManager.TAG_OVERLAY, "FloatingOverlayService уничтожен")
    }

    private fun removeOverlay() {
        rootContainer?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // Игнорируем если уже удален
            }
        }
        rootContainer = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlay() {
        val settings = settingsRepo.getLatestSettings()

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = settings.overlayX.coerceAtLeast(20)
            y = settings.overlayY.coerceAtLeast(50)
        }

        rootContainer = FrameLayout(this)

        // 1. Создаем развернутую панель
        createExpandedPanel()

        // 2. Создаем маленькую плавающую кнопку
        createMinimizedBadge()

        rootContainer?.addView(expandedLayout)
        rootContainer?.addView(floatingBadgeButton)

        // По умолчанию показываем свернутую кнопку
        setMode(minimized = false)

        try {
            windowManager.addView(rootContainer, overlayParams)
        } catch (e: Exception) {
            EventLogManager.log(
                EventLogManager.TAG_OVERLAY,
                "ERROR: Не удалось добавить оверлей: ${e.message}",
                isError = true
            )
        }
    }

    private fun setMode(minimized: Boolean) {
        isMinimized = minimized
        if (minimized) {
            expandedLayout.visibility = View.GONE
            floatingBadgeButton.visibility = View.VISIBLE
        } else {
            expandedLayout.visibility = View.VISIBLE
            floatingBadgeButton.visibility = View.GONE
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createMinimizedBadge() {
        floatingBadgeButton = Button(this).apply {
            val sizePx = dpToPx(52)
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
            text = "▶"
            textSize = 20f
            setTextColor(Color.parseColor("#00E676"))
            background = createBadgeBg()
            gravity = Gravity.CENTER
            elevation = dpToPx(8).toFloat()

            // Перемещение пальцем плавающей кнопки
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var isClick = false

            setOnTouchListener { _, event ->
                val params = overlayParams ?: return@setOnTouchListener false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isClick = true
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (abs(dx) > 10 || abs(dy) > 10) {
                            isClick = false
                        }
                        params.x = initialX + dx
                        params.y = initialY + dy
                        try {
                            windowManager.updateViewLayout(rootContainer, params)
                        } catch (e: Exception) {
                            // Игнорируем
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isClick) {
                            // Разворачиваем панель
                            setMode(minimized = false)
                        } else {
                            settingsRepo.updateOverlayPosition(params.x, params.y)
                        }
                        true
                    }
                    else -> false
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createExpandedPanel() {
        val panelWidth = dpToPx(270)
        expandedLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(panelWidth, FrameLayout.LayoutParams.WRAP_CONTENT)
            background = createPanelBg()
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(12))
            elevation = dpToPx(10).toFloat()
        }

        // Заголовок панели с возможностью перетаскивания
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dpToPx(6))
        }

        val titleText = TextView(this).apply {
            text = "AUTO CLICKER"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        minimizeBtn = Button(this).apply {
            text = "—"
            textSize = 14f
            setTextColor(Color.parseColor("#B0BEC5"))
            background = createButtonBg(Color.parseColor("#33455A64"), dpToPx(6))
            val btnSize = dpToPx(28)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            setOnClickListener {
                setMode(minimized = true)
            }
        }

        headerLayout.addView(titleText)
        headerLayout.addView(minimizeBtn)
        expandedLayout.addView(headerLayout)

        // Перетаскивание за шапку панели
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        headerLayout.setOnTouchListener { _, event ->
            val params = overlayParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager.updateViewLayout(rootContainer, params)
                    } catch (e: Exception) {
                        // Игнорируем
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    settingsRepo.updateOverlayPosition(params.x, params.y)
                    true
                }
                else -> false
            }
        }

        // STATUS
        statusText = TextView(this).apply {
            text = "STATUS: STOPPED"
            setTextColor(Color.parseColor("#FF5252"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dpToPx(2), 0, dpToPx(2))
        }
        expandedLayout.addView(statusText)

        // NEXT ACTION
        nextActionText = TextView(this).apply {
            text = "NEXT ACTION: --"
            setTextColor(Color.parseColor("#64B5F6"))
            textSize = 11f
            setPadding(0, 0, 0, dpToPx(2))
        }
        expandedLayout.addView(nextActionText)

        // TIMER
        timerText = TextView(this).apply {
            text = "TIMER: --"
            setTextColor(Color.parseColor("#FFD54F"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dpToPx(2), 0, dpToPx(8))
        }
        expandedLayout.addView(timerText)

        // Кнопки START и STOP
        val controlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dpToPx(6))
        }

        startBtn = Button(this).apply {
            text = "START"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            background = createButtonBg(Color.parseColor("#FF00C853"), dpToPx(8))
            val lp = LinearLayout.LayoutParams(0, dpToPx(38), 1f).apply {
                marginEnd = dpToPx(4)
            }
            layoutParams = lp
            setOnClickListener {
                cycleController.start()
            }
        }

        stopBtn = Button(this).apply {
            text = "STOP"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            background = createButtonBg(Color.parseColor("#FFF44336"), dpToPx(8))
            val lp = LinearLayout.LayoutParams(0, dpToPx(38), 1f).apply {
                marginStart = dpToPx(4)
            }
            layoutParams = lp
            setOnClickListener {
                cycleController.stop()
            }
        }

        controlRow.addView(startBtn)
        controlRow.addView(stopBtn)
        expandedLayout.addView(controlRow)

        // Кнопка SETTINGS (открывает MainActivity поверх игры)
        settingsBtn = Button(this).apply {
            text = "SETTINGS"
            setTextColor(Color.parseColor("#E0E0E0"))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            background = createButtonBg(Color.parseColor("#263238"), dpToPx(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(34)
            ).apply {
                bottomMargin = dpToPx(6)
            }
            setOnClickListener {
                openMainActivity()
            }
        }
        expandedLayout.addView(settingsBtn)

        // Кнопка MINIMIZE
        val bottomMinimizeBtn = Button(this).apply {
            text = "MINIMIZE"
            setTextColor(Color.parseColor("#90A4AE"))
            textSize = 11f
            background = createButtonBg(Color.parseColor("#1C242B"), dpToPx(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(32)
            )
            setOnClickListener {
                setMode(minimized = true)
            }
        }
        expandedLayout.addView(bottomMinimizeBtn)

        // Быстрые кнопки TEST 1..3 для проверки жестов
        val quickTestRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dpToPx(6), 0, 0)
        }
        quickTestRow.addView(createQuickTestBtn("T1", 1))
        quickTestRow.addView(createQuickTestBtn("T2", 2))
        quickTestRow.addView(createQuickTestBtn("T3", 3))
        expandedLayout.addView(quickTestRow)
    }

    private fun createQuickTestBtn(label: String, pointId: Int): Button {
        return Button(this).apply {
            text = label
            textSize = 10f
            setTextColor(Color.WHITE)
            background = createButtonBg(Color.parseColor("#2A3B4C"), dpToPx(6))
            val lp = LinearLayout.LayoutParams(0, dpToPx(28), 1f).apply {
                marginEnd = dpToPx(3)
            }
            layoutParams = lp
            setOnClickListener {
                cycleController.testClick(pointId)
            }
        }
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    private fun observeControllerState() {
        // Подписка на статус
        serviceScope.launch {
            cycleController.status.collect { status ->
                val cycleNum = cycleController.cycleNumber.value
                val statusString = when (status) {
                    CycleStatus.STOPPED -> "STATUS: STOPPED"
                    CycleStatus.RUNNING -> if (cycleNum > 0) "STATUS: RUNNING (#$cycleNum)" else "STATUS: RUNNING"
                    CycleStatus.WAITING_CYCLE -> if (cycleNum > 0) "STATUS: WAITING (#$cycleNum)" else "STATUS: WAITING"
                }
                statusText.text = statusString
                statusText.setTextColor(
                    when (status) {
                        CycleStatus.RUNNING -> Color.parseColor("#00E676")
                        CycleStatus.WAITING_CYCLE -> Color.parseColor("#FFD54F")
                        CycleStatus.STOPPED -> Color.parseColor("#FF5252")
                    }
                )

                // Обновляем вид свернутой плавающей кнопки
                if (status == CycleStatus.RUNNING || status == CycleStatus.WAITING_CYCLE) {
                    floatingBadgeButton.text = "■"
                    floatingBadgeButton.setTextColor(Color.parseColor("#FF5252"))
                } else {
                    floatingBadgeButton.text = "▶"
                    floatingBadgeButton.setTextColor(Color.parseColor("#00E676"))
                }
            }
        }

        // Подписка на следующее действие
        serviceScope.launch {
            cycleController.nextAction.collect { action ->
                nextActionText.text = "NEXT ACTION: $action"
            }
        }

        // Подписка на таймер
        serviceScope.launch {
            cycleController.countdownText.collect { cd ->
                timerText.text = if (cd.isNotEmpty()) "TIMER: $cd" else "TIMER: --"
            }
        }
    }

    private fun createPanelBg(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(14).toFloat()
            setColor(Color.parseColor("#E6121622")) // 90% полупрозрачный темный фон
            setStroke(dpToPx(1), Color.parseColor("#443A4560"))
        }
    }

    private fun createBadgeBg(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#D9161B26")) // 85% полупрозрачный
            setStroke(dpToPx(2), Color.parseColor("#662979FF"))
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

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, FloatingOverlayService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingOverlayService::class.java)
            context.stopService(intent)
        }
    }
}
