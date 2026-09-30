package com.example.autoclicker.service

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
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
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.example.autoclicker.MainActivity
import com.example.autoclicker.data.ClickPoint
import com.example.autoclicker.data.ClickerSettings
import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.engine.CycleController
import com.example.autoclicker.engine.CycleStatus
import com.example.autoclicker.engine.GestureExecutor
import com.example.autoclicker.engine.TapHooks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Плавающий оверлей для работы поверх игр (включая PUBG Mobile).
 *
 * Режимы:
 * 1. Свернутый (MINIMIZED) — маленькая перетаскиваемая круглая кнопка [ ▶ ] / [ ■ ].
 *    По умолчанию стартует в свернутом виде. Позиция запоминается.
 * 2. Развернутый (EXPANDED) — полноценная панель управления:
 *    - Таймер цикла со степпером «−» / «+» (1..15 мин).
 *    - Три строки Point 1 / Point 2 / Point 3 с координатами, «Установить», «Тест», вкл/выкл.
 *    - Кнопки START и STOP, статус, обратный отсчет, следующее действие.
 *    - Кнопка «Показать/скрыть точки».
 *    - Предупреждение о перекрытии точек панелью.
 *    - Строка последнего нажатия с результатом.
 *    - Кнопка «Закрыть AutoClicker» в аварийном меню.
 * 3. Реализует TapHooks (beforeTap / afterTap) для временного снятия касаний и показа анимации.
 */
class FloatingOverlayService : Service(), TapHooks {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var windowManager: WindowManager
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var cycleController: CycleController
    private val gestureExecutor = GestureExecutor()

    private var overlayParams: WindowManager.LayoutParams? = null
    private var rootContainer: FrameLayout? = null

    // Полноэкранный оверлей постоянных меток и анимации волны
    private var visualOverlay: PointsVisualOverlayView? = null

    // Развернутая панель
    private lateinit var expandedLayout: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var nextActionText: TextView
    private lateinit var timerText: TextView
    private lateinit var lastTapText: TextView
    private lateinit var overlapWarningText: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var togglePointsBtn: Button

    // Степпер таймера
    private lateinit var timerValueText: TextView

    // Строки точек
    private lateinit var p1CoordsText: TextView
    private lateinit var p1Switch: Switch
    private lateinit var p2CoordsText: TextView
    private lateinit var p2Switch: Switch
    private lateinit var p3CoordsText: TextView
    private lateinit var p3Switch: Switch

    // Свернутая плавающая кнопка
    private lateinit var floatingBadgeButton: Button

    private var isMinimized = true
    private var activeCalibrationView: CalibrationOverlayView? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        settingsRepo = SettingsRepository.getInstance(applicationContext)
        cycleController = CycleController.getInstance(gestureExecutor, settingsRepo)
        cycleController.tapHooks = this

        // 1. Создаем полноэкранный слой постоянных меток точек и обратной связи
        visualOverlay = PointsVisualOverlayView(this).apply {
            attachToWindow()
            updateSettings(settingsRepo.getLatestSettings())
        }

        // 2. Создаем плавающее окно
        createOverlay()
        observeControllerState()
        observeSettingsChanges()

        EventLogManager.log(EventLogManager.TAG_OVERLAY, "FloatingOverlayService запущен (свернут)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AutoClickForegroundService.start(applicationContext)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        shutdownAllWindowsAndServices()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        shutdownAllWindowsAndServices()
        super.onDestroy()
    }

    private fun shutdownAllWindowsAndServices() {
        cycleController.tapHooks = null
        cycleController.stop()
        activeCalibrationView?.dismiss()
        activeCalibrationView = null
        visualOverlay?.cancelAnimations()
        visualOverlay?.detachFromWindow()
        visualOverlay = null
        removeOverlay()
        serviceScope.cancel()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        stopSelf()
        EventLogManager.log(EventLogManager.TAG_OVERLAY, "FloatingOverlayService остановлен")
    }

    // --- Реализация TapHooks (Requirement Д и Е) ---

    override suspend fun beforeTap(pointId: Int, x: Float, y: Float, tapIndex: Int, totalTaps: Int) {
        // Делаем плавающую кнопку и панель сквозными (FLAG_NOT_TOUCHABLE) перед кликом
        withContext(Dispatchers.Main) {
            makeOverlayNonTouchable(true)
        }
        delay(60L)
    }

    override suspend fun afterTap(pointId: Int, x: Float, y: Float, success: Boolean, tapIndex: Int, totalTaps: Int) {
        // Запуск анимации волны расширяющегося кольца в точке клика
        withContext(Dispatchers.Main) {
            visualOverlay?.showTapRipple(pointId, x, y, success, tapIndex, totalTaps)

            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val statusStr = if (success) "OK" else "ERROR"
            val text = "Последнее нажатие: P$pointId (${x.toInt()}, ${y.toInt()}) $statusStr, $timeStr"
            lastTapText.text = text
            lastTapText.setTextColor(if (success) Color.parseColor("#00E676") else Color.parseColor("#FF5252"))
        }

        // Небольшой запас 150 мс перед возвратом кликабельности панели
        delay(150L)
        withContext(Dispatchers.Main) {
            makeOverlayNonTouchable(false)
        }
    }

    private fun makeOverlayNonTouchable(nonTouchable: Boolean) {
        val params = overlayParams ?: return
        val root = rootContainer ?: return
        if (nonTouchable) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        try {
            windowManager.updateViewLayout(root, params)
        } catch (_: Exception) {}
    }

    private fun removeOverlay() {
        rootContainer?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        rootContainer = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlay() {
        val settings = settingsRepo.getLatestSettings()

        val layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        rootContainer = FrameLayout(this)

        // 1. Создаем развернутую панель
        createExpandedPanel()

        // 2. Создаем маленькую плавающую кнопку
        createMinimizedBadge()

        rootContainer?.addView(expandedLayout)
        rootContainer?.addView(floatingBadgeButton)

        // Сервис стартует в СВЕРНУТОМ виде (Requirement А)
        setMode(minimized = true)

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
            refreshPointsUi()
            checkOverlapWarning()
        }
    }

    private fun showCloseAutoClickerDialog() {
        AlertDialog.Builder(this)
            .setTitle("Закрыть AutoClicker")
            .setPositiveButton("Закрыть") { _, _ ->
                shutdownAllWindowsAndServices()
                AutoClickForegroundService.stop(applicationContext)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createMinimizedBadge() {
        floatingBadgeButton = Button(this).apply {
            val sizePx = dpToPx(50)
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
            text = "▶"
            textSize = 20f
            setTextColor(Color.parseColor("#00E676"))
            background = createBadgeBg()
            gravity = Gravity.CENTER
            elevation = dpToPx(8).toFloat()

            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var isClick = false

            setOnLongClickListener {
                showCloseAutoClickerDialog()
                true
            }

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
                        } catch (_: Exception) {}
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
        val panelWidth = dpToPx(280)
        expandedLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(panelWidth, FrameLayout.LayoutParams.WRAP_CONTENT)
            background = createPanelBg()
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(12))
            elevation = dpToPx(10).toFloat()
        }

        // 1. Шапка панели (Заголовок, переключатель видимости точек, сворачивание)
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dpToPx(4))
        }

        val titleText = TextView(this).apply {
            text = "AUTO CLICKER"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        togglePointsBtn = Button(this).apply {
            text = "👁 Метки"
            textSize = 10f
            setTextColor(Color.parseColor("#80D8FF"))
            background = createButtonBg(Color.parseColor("#263238"), dpToPx(6))
            val lp = LinearLayout.LayoutParams(dpToPx(68), dpToPx(26)).apply {
                marginEnd = dpToPx(4)
            }
            layoutParams = lp
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                val overlay = visualOverlay ?: return@setOnClickListener
                overlay.showPoints = !overlay.showPoints
                text = if (overlay.showPoints) "👁 Метки" else "🙈 Скрыты"
                setTextColor(if (overlay.showPoints) Color.parseColor("#80D8FF") else Color.parseColor("#90A4AE"))
            }
        }

        val minimizeBtn = Button(this).apply {
            text = "—"
            textSize = 14f
            setTextColor(Color.parseColor("#B0BEC5"))
            background = createButtonBg(Color.parseColor("#33455A64"), dpToPx(6))
            val btnSize = dpToPx(26)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                setMode(minimized = true)
            }
        }

        val closeBtn = Button(this).apply {
            text = "✕"
            textSize = 12f
            setTextColor(Color.parseColor("#B0BEC5"))
            background = createButtonBg(Color.parseColor("#33455A64"), dpToPx(6))
            val btnSize = dpToPx(26)
            val lp = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                marginStart = dpToPx(4)
            }
            layoutParams = lp
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                shutdownAllWindowsAndServices()
                AutoClickForegroundService.stop(applicationContext)
            }
        }

        headerLayout.addView(titleText)
        headerLayout.addView(togglePointsBtn)
        headerLayout.addView(minimizeBtn)
        headerLayout.addView(closeBtn)
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
                    } catch (_: Exception) {}
                    checkOverlapWarning()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    settingsRepo.updateOverlayPosition(params.x, params.y)
                    checkOverlapWarning()
                    true
                }
                else -> false
            }
        }

        // 2. Прокручиваемое содержимое панели
        val scrollView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Предупреждение о перекрытии точек панелью
        overlapWarningText = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#FFD54F"))
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            visibility = View.GONE
            setPadding(0, dpToPx(2), 0, dpToPx(4))
        }
        scrollContent.addView(overlapWarningText)

        // STATUS
        statusText = TextView(this).apply {
            text = "STATUS: STOPPED"
            setTextColor(Color.parseColor("#FF5252"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dpToPx(2), 0, dpToPx(1))
        }
        scrollContent.addView(statusText)

        // NEXT ACTION
        nextActionText = TextView(this).apply {
            text = "NEXT: --"
            setTextColor(Color.parseColor("#64B5F6"))
            textSize = 11f
            setPadding(0, 0, 0, dpToPx(1))
        }
        scrollContent.addView(nextActionText)

        // TIMER
        timerText = TextView(this).apply {
            text = "TIMER: --"
            setTextColor(Color.parseColor("#FFD54F"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dpToPx(1), 0, dpToPx(1))
        }
        scrollContent.addView(timerText)

        // LAST TAP FEEDBACK
        lastTapText = TextView(this).apply {
            text = "Последнее нажатие: --"
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 10f
            setPadding(0, 0, 0, dpToPx(6))
        }
        scrollContent.addView(lastTapText)

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
            background = createButtonBg(Color.parseColor("#00C853"), dpToPx(8))
            val lp = LinearLayout.LayoutParams(0, dpToPx(36), 1f).apply {
                marginEnd = dpToPx(4)
            }
            layoutParams = lp
            setOnClickListener {
                cycleController.start()
                // При нажатии START панель автоматически сворачивается (Requirement Е)
                setMode(minimized = true)
            }
        }

        stopBtn = Button(this).apply {
            text = "STOP"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            background = createButtonBg(Color.parseColor("#37474F"), dpToPx(8))
            isEnabled = false
            alpha = 0.5f
            val lp = LinearLayout.LayoutParams(0, dpToPx(36), 1f).apply {
                marginStart = dpToPx(4)
            }
            layoutParams = lp
            setOnClickListener {
                cycleController.stop()
                visualOverlay?.cancelAnimations()
            }
        }

        controlRow.addView(startBtn)
        controlRow.addView(stopBtn)
        scrollContent.addView(controlRow)

        // РАЗДЕЛ ТАЙМЕРА СО СТЕППЕРОМ «−» / «+» (Requirement Б)
        val timerCard = createTimerStepperCard()
        scrollContent.addView(timerCard)

        // СТРОКИ POINT 1, POINT 2, POINT 3 (Requirement Б)
        val p1Row = createPointRow(1)
        val p2Row = createPointRow(2)
        val p3Row = createPointRow(3)
        scrollContent.addView(p1Row)
        scrollContent.addView(p2Row)
        scrollContent.addView(p3Row)

        // Кнопка открыть Настройки в MainActivity
        val appSettingsBtn = Button(this).apply {
            text = "НАСТРОЙКИ ПРИЛОЖЕНИЯ"
            setTextColor(Color.parseColor("#90A4AE"))
            textSize = 10f
            background = createButtonBg(Color.parseColor("#1C242B"), dpToPx(6))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(28)
            ).apply {
                topMargin = dpToPx(4)
            }
            layoutParams = lp
            setOnClickListener {
                openMainActivity()
            }
        }
        scrollContent.addView(appSettingsBtn)

        scrollView.addView(scrollContent)
        expandedLayout.addView(scrollView)
    }

    private fun createTimerStepperCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = createButtonBg(Color.parseColor("#1A212D"), dpToPx(6))
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(6)
            }
            layoutParams = lp

            val label = TextView(this@FloatingOverlayService).apply {
                text = "Таймер цикла:"
                setTextColor(Color.WHITE)
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(label)

            val minusBtn = Button(this@FloatingOverlayService).apply {
                text = "−"
                textSize = 14f
                setTextColor(Color.WHITE)
                background = createButtonBg(Color.parseColor("#37474F"), dpToPx(4))
                layoutParams = LinearLayout.LayoutParams(dpToPx(28), dpToPx(26))
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    val current = settingsRepo.getLatestSettings().cycleDelayMinutes
                    if (current > 1) {
                        settingsRepo.updateCycleDelay(current - 1)
                    }
                }
            }
            addView(minusBtn)

            timerValueText = TextView(this@FloatingOverlayService).apply {
                val delayMin = settingsRepo.getLatestSettings().cycleDelayMinutes
                text = "$delayMin мин"
                setTextColor(Color.parseColor("#FFD54F"))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dpToPx(6), 0, dpToPx(6), 0)
            }
            addView(timerValueText)

            val plusBtn = Button(this@FloatingOverlayService).apply {
                text = "+"
                textSize = 14f
                setTextColor(Color.WHITE)
                background = createButtonBg(Color.parseColor("#37474F"), dpToPx(4))
                layoutParams = LinearLayout.LayoutParams(dpToPx(28), dpToPx(26))
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    val current = settingsRepo.getLatestSettings().cycleDelayMinutes
                    if (current < 15) {
                        settingsRepo.updateCycleDelay(current + 1)
                    }
                }
            }
            addView(plusBtn)
        }
    }

    private fun createPointRow(pointId: Int): LinearLayout {
        val rowLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createButtonBg(Color.parseColor("#151C26"), dpToPx(6))
            setPadding(dpToPx(8), dpToPx(5), dpToPx(8), dpToPx(5))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(4)
            }
            layoutParams = lp
        }

        // Верхний ряд: Заголовок точки и Свитч вкл/выкл
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val pointTitle = TextView(this).apply {
            val titleColor = when (pointId) {
                1 -> Color.parseColor("#00E5FF")
                2 -> Color.parseColor("#FFAB00")
                3 -> Color.parseColor("#00E676")
                else -> Color.WHITE
            }
            text = "POINT $pointId"
            setTextColor(titleColor)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topRow.addView(pointTitle)

        val switchView = Switch(this).apply {
            val point = settingsRepo.getLatestSettings().getPointById(pointId)
            isChecked = point.enabled
            setOnCheckedChangeListener { _, isChecked ->
                val p = settingsRepo.getLatestSettings().getPointById(pointId)
                settingsRepo.updatePointConfig(pointId, isChecked, p.clickCount, p.intervalSec)
            }
        }
        when (pointId) {
            1 -> p1Switch = switchView
            2 -> p2Switch = switchView
            3 -> p3Switch = switchView
        }
        topRow.addView(switchView)
        rowLayout.addView(topRow)

        // Средний ряд: Координаты
        val coordsView = TextView(this).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dpToPx(4))
        }
        when (pointId) {
            1 -> p1CoordsText = coordsView
            2 -> p2CoordsText = coordsView
            3 -> p3CoordsText = coordsView
        }
        rowLayout.addView(coordsView)

        // Нижний ряд: Кнопки «Установить» и «Тест»
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val setBtn = Button(this).apply {
            text = "Установить"
            textSize = 10f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = createButtonBg(Color.parseColor("#1565C0"), dpToPx(4))
            val lp = LinearLayout.LayoutParams(0, dpToPx(28), 1f).apply {
                marginEnd = dpToPx(4)
            }
            layoutParams = lp
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                startCalibration(pointId)
            }
        }

        val testBtn = Button(this).apply {
            text = "Тест"
            textSize = 10f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = createButtonBg(Color.parseColor("#2E7D32"), dpToPx(4))
            val lp = LinearLayout.LayoutParams(0, dpToPx(28), 1f)
            layoutParams = lp
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                cycleController.testClick(pointId)
            }
        }

        actionsRow.addView(setBtn)
        actionsRow.addView(testBtn)
        rowLayout.addView(actionsRow)

        return rowLayout
    }

    private fun startCalibration(pointId: Int) {
        // Закрываем панель на время калибровки
        setMode(minimized = true)
        floatingBadgeButton.visibility = View.GONE // Прячем даже круглую кнопку

        activeCalibrationView?.dismiss()
        val cal = CalibrationOverlayView(
            context = this,
            currentPointId = pointId,
            onCoordinateCaptured = { capturedId, x, y ->
                Toast.makeText(
                    this,
                    "P$capturedId сохранён: (${x.toInt()}, ${y.toInt()})",
                    Toast.LENGTH_SHORT
                ).show()
            },
            onDismissed = {
                activeCalibrationView = null
                setMode(minimized = false)
                floatingBadgeButton.visibility = View.VISIBLE
            }
        )
        activeCalibrationView = cal
        cal.show()

        serviceScope.launch {
            delay(60_000L)
            if (activeCalibrationView === cal) {
                cal.dismiss()
            }
        }
    }

    private fun refreshPointsUi() {
        val settings = settingsRepo.getLatestSettings()
        timerValueText.text = "${settings.cycleDelayMinutes} мин"

        updatePointRowUi(settings.point1, p1CoordsText, p1Switch)
        updatePointRowUi(settings.point2, p2CoordsText, p2Switch)
        updatePointRowUi(settings.point3, p3CoordsText, p3Switch)

        visualOverlay?.updateSettings(settings)
    }

    private fun updatePointRowUi(point: ClickPoint, coordsView: TextView, switchView: Switch) {
        switchView.isChecked = point.enabled
        if (point.isConfigured) {
            coordsView.text = "X=${point.x.toInt()}  Y=${point.y.toInt()}"
            coordsView.setTextColor(Color.parseColor("#00E5FF"))
        } else {
            coordsView.text = "Координаты не заданы"
            coordsView.setTextColor(Color.parseColor("#EF5350"))
        }
    }

    private fun checkOverlapWarning() {
        val params = overlayParams ?: return
        val panelW = expandedLayout.width.takeIf { it > 0 } ?: dpToPx(280)
        val panelH = expandedLayout.height.takeIf { it > 0 } ?: dpToPx(340)
        val panelRect = Rect(params.x, params.y, params.x + panelW, params.y + panelH)

        val settings = settingsRepo.getLatestSettings()
        val overlapping = mutableListOf<String>()
        if (settings.point1.isConfigured && panelRect.contains(settings.point1.x.toInt(), settings.point1.y.toInt())) {
            overlapping.add("P1")
        }
        if (settings.point2.isConfigured && panelRect.contains(settings.point2.x.toInt(), settings.point2.y.toInt())) {
            overlapping.add("P2")
        }
        if (settings.point3.isConfigured && panelRect.contains(settings.point3.x.toInt(), settings.point3.y.toInt())) {
            overlapping.add("P3")
        }

        if (overlapping.isNotEmpty()) {
            overlapWarningText.visibility = View.VISIBLE
            overlapWarningText.text = "⚠ Внимание: ${overlapping.joinToString(", ")} перекрыта панелью!\nСверните или переместите её."
        } else {
            overlapWarningText.visibility = View.GONE
        }
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    private fun observeSettingsChanges() {
        serviceScope.launch {
            settingsRepo.settings.collect { settings ->
                refreshPointsUi()
                checkOverlapWarning()
            }
        }
    }

    private fun observeControllerState() {
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

                if (status == CycleStatus.RUNNING || status == CycleStatus.WAITING_CYCLE) {
                    startBtn.text = "▶ ЗАПУЩЕНО"
                    startBtn.background = createButtonBg(Color.parseColor("#00B0FF"), dpToPx(8))
                    startBtn.isEnabled = false
                    startBtn.alpha = 1.0f

                    stopBtn.isEnabled = true
                    stopBtn.alpha = 1.0f
                    stopBtn.background = createButtonBg(Color.parseColor("#FF1744"), dpToPx(8))

                    floatingBadgeButton.text = "■"
                    floatingBadgeButton.setTextColor(Color.parseColor("#FF5252"))
                } else {
                    startBtn.text = "START"
                    startBtn.background = createButtonBg(Color.parseColor("#00C853"), dpToPx(8))
                    startBtn.isEnabled = true
                    startBtn.alpha = 1.0f

                    stopBtn.isEnabled = false
                    stopBtn.alpha = 0.5f
                    stopBtn.background = createButtonBg(Color.parseColor("#37474F"), dpToPx(8))

                    floatingBadgeButton.text = "▶"
                    floatingBadgeButton.setTextColor(Color.parseColor("#00E676"))
                }
            }
        }

        serviceScope.launch {
            cycleController.nextAction.collect { action ->
                nextActionText.text = "NEXT: $action"
            }
        }

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
            setColor(Color.parseColor("#E6121622"))
            setStroke(dpToPx(1), Color.parseColor("#443A4560"))
        }
    }

    private fun createBadgeBg(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#D9161B26"))
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
