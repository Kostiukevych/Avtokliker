package com.example.autoclicker.service

import android.animation.ValueAnimator
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
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.autoclicker.MainActivity
import com.example.autoclicker.data.ClickerSettings
import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.data.SmartConfigManager
import com.example.autoclicker.data.SwipeAction
import com.example.autoclicker.engine.CycleController
import com.example.autoclicker.engine.CycleStatus
import com.example.autoclicker.engine.GestureExecutor
import com.example.autoclicker.engine.MacroController
import com.example.autoclicker.engine.SmartEngine
import com.example.autoclicker.engine.SwipeController
import com.example.autoclicker.engine.TapHooks
import com.example.autoclicker.ui.theme.NeonTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Плавающий оверлей в стиле liquid-glass-neon:
 * - Свёрнутая кнопка: перетаскиваемая стеклянная таблетка с неоновым свечением.
 * - Компактная развёрнутая панель (не шире 85% и не выше 60% экрана):
 *   - Заголовок «Автокликер» с «—» и «✕»
 *   - Компактный статус (STATUS, NEXT, TIMER, последнее нажатие)
 *   - Кнопки START и STOP
 *   - Ряд кнопок: [Точки] [Свайпы] [Умный] [Ещё]
 * - Отдельные окна настроек SettingsPopupWindow (TYPE_APPLICATION_OVERLAY)
 * - Анимация неона через ValueAnimator (36с, на паузе при свёрнутой панели)
 */
class FloatingOverlayService : Service(), TapHooks, SettingsPopupWindow.Callbacks {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var windowManager: WindowManager
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var configManager: SmartConfigManager
    private lateinit var cycleController: CycleController
    private lateinit var smartEngine: SmartEngine
    private lateinit var swipeController: SwipeController
    private lateinit var macroController: MacroController
    private val gestureExecutor = GestureExecutor()

    private var overlayParams: WindowManager.LayoutParams? = null
    private var rootContainer: FrameLayout? = null

    // Полноэкранный слой меток
    private var visualOverlay: PointsVisualOverlayView? = null

    // Развёрнутая компактная панель
    private lateinit var expandedLayout: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var nextActionText: TextView
    private lateinit var timerText: TextView
    private lateinit var lastTapText: TextView
    private lateinit var overlapWarningText: TextView
    private lateinit var startBtn: NeonGlassButton
    private lateinit var stopBtn: NeonGlassButton

    // Кнопки открытия окон
    private lateinit var btnPoints: Button
    private lateinit var btnSwipes: Button
    private lateinit var btnSmart: Button
    private lateinit var btnMore: Button

    // Кнопки групп запуска «Что запускать»
    private lateinit var btnRunPoints: NeonGlassButton
    private lateinit var btnRunSwipes: NeonGlassButton
    private lateinit var btnRunSmart: NeonGlassButton

    // Свёрнутая кнопка
    private lateinit var floatingBadgeButton: NeonGlassButton

    // Отдельное окно настроек
    private lateinit var popupWindow: SettingsPopupWindow

    private var isMinimized = true
    private var activeCalibrationView: CalibrationOverlayView? = null

    // Неоновый аниматор: плавный перебор оттенка за 36 секунд
    private var neonAnimator: ValueAnimator? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        settingsRepo = SettingsRepository.getInstance(applicationContext)
        configManager = SmartConfigManager.getInstance(applicationContext)
        cycleController = CycleController.getInstance(gestureExecutor, settingsRepo)
        cycleController.tapHooks = this
        smartEngine = SmartEngine.getInstance(applicationContext, gestureExecutor, settingsRepo)
        smartEngine.tapHooks = this
        swipeController = SwipeController.getInstance(gestureExecutor, settingsRepo)
        macroController = MacroController.getInstance(gestureExecutor, settingsRepo)

        popupWindow = SettingsPopupWindow(
            context = this,
            windowManager = windowManager,
            settingsRepo = settingsRepo,
            configManager = configManager,
            cycleController = cycleController,
            smartEngine = smartEngine,
            swipeController = swipeController,
            macroController = macroController,
            callbacks = this
        )

        // Полноэкранный слой меток точек
        visualOverlay = PointsVisualOverlayView(this).apply {
            attachToWindow()
            updateSettings(settingsRepo.getLatestSettings())
        }

        setupNeonAnimator()
        createOverlay()
        observeControllerState()
        observeSettingsChanges()

        EventLogManager.log(EventLogManager.TAG_OVERLAY, "FloatingOverlayService запущен (свёрнут)")
    }

    private fun setupNeonAnimator() {
        // Цвет неона считает NeonTheme (общий с главным экраном); нативные View сами подписаны на него.
        NeonTheme.init(applicationContext)
    }

    private fun updateNeonBorders() {
        popupWindow.updateNeonColor()
    }

    private fun createOverlay() {
        val settings = settingsRepo.getLatestSettings()
        val (sw, sh) = screenSize()

        val initialX = settings.overlayX.coerceIn(0, sw - dpToPx(80))
        val initialY = settings.overlayY.coerceIn(0, sh - dpToPx(80))

        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }

        rootContainer = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }

        createMinimizedBadge()
        createExpandedPanel()

        rootContainer?.addView(expandedLayout)
        rootContainer?.addView(floatingBadgeButton)

        setMode(minimized = true)

        try {
            windowManager.addView(rootContainer, overlayParams)
        } catch (e: Exception) {
            EventLogManager.log(
                EventLogManager.TAG_OVERLAY,
                "ERROR: не удалось добавить плавающий оверлей: ${e.message}",
                isError = true
            )
        }
    }

    private fun setMode(minimized: Boolean) {
        isMinimized = minimized
        if (minimized) {
            neonAnimator?.pause()
            popupWindow.dismiss()
            expandedLayout.visibility = View.GONE
            floatingBadgeButton.visibility = View.VISIBLE
        } else {
            floatingBadgeButton.visibility = View.GONE
            expandedLayout.visibility = View.VISIBLE
            if (neonAnimator?.isPaused == true) neonAnimator?.resume() else neonAnimator?.start()
            checkOverlapWarning()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createMinimizedBadge() {
        floatingBadgeButton = NeonGlassButton(this).apply {
            val sizePx = dpToPx(56)
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
            text = "▶"
            textSize = 20f
            setTextColor(Color.parseColor("#00E676"))
            cornerDp = 999f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)

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
        val (sw, sh) = screenSize()
        val panelWidth = (sw * 0.82f).toInt().coerceIn(dpToPx(250), dpToPx(320))
        val maxPanelHeight = (sh * 0.60f).toInt()

        expandedLayout = NeonPanel(this, 24f).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(panelWidth, FrameLayout.LayoutParams.WRAP_CONTENT)
            setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(14))
        }

        // 1. Шапка панели: «Автокликер» с «—» и «✕»
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dpToPx(6))
        }

        val titleText = TextView(this).apply {
            text = "Автокликер"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val minimizeBtn = NeonGlassButton(this).apply {
            text = "—"
            textSize = 14f
            setTextColor(Color.WHITE)
            cornerDp = 12f
            hueOffset = 30f
            val btnSize = dpToPx(32)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                setMode(minimized = true)
            }
        }

        val closeBtn = NeonGlassButton(this).apply {
            text = "✕"
            textSize = 12f
            setTextColor(Color.WHITE)
            cornerDp = 12f
            hueOffset = 30f
            val btnSize = dpToPx(32)
            val lp = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                marginStart = dpToPx(6)
            }
            layoutParams = lp
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                shutdownAllWindowsAndServices()
                AutoClickForegroundService.stop(applicationContext)
            }
        }

        headerLayout.addView(titleText)
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
            val (currentSw, currentSh) = screenSize()
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val targetX = initialX + (event.rawX - initialTouchX).toInt()
                    val targetY = initialY + (event.rawY - initialTouchY).toInt()
                    params.x = targetX.coerceIn(0, (currentSw - dpToPx(60)).coerceAtLeast(0))
                    params.y = targetY.coerceIn(0, (currentSh - dpToPx(80)).coerceAtLeast(0))
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

        // Предупреждение о перекрытии
        overlapWarningText = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#FFD54F"))
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            visibility = View.GONE
            setPadding(0, 0, 0, dpToPx(2))
        }
        expandedLayout.addView(overlapWarningText)

        // 2. Строки статуса (компактно)
        statusText = TextView(this).apply {
            text = "STATUS: STOPPED"
            setTextColor(Color.parseColor("#FF5252"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dpToPx(1), 0, dpToPx(1))
        }
        expandedLayout.addView(statusText)

        nextActionText = TextView(this).apply {
            text = "NEXT: --"
            setTextColor(Color.parseColor("#64B5F6"))
            textSize = 11f
            setPadding(0, 0, 0, dpToPx(1))
        }
        expandedLayout.addView(nextActionText)

        timerText = TextView(this).apply {
            text = "TIMER: --"
            setTextColor(Color.parseColor("#FFD54F"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dpToPx(1))
        }
        expandedLayout.addView(timerText)

        lastTapText = TextView(this).apply {
            text = "Последнее нажатие: --"
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 10f
            setPadding(0, 0, 0, dpToPx(6))
        }
        expandedLayout.addView(lastTapText)

        // 3. Ряд кнопок START / STOP
        val controlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dpToPx(8))
        }

        startBtn = NeonGlassButton(this).apply {
            text = "START"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            cornerDp = 16f
            val lp = LinearLayout.LayoutParams(0, dpToPx(44), 1f).apply { marginEnd = dpToPx(6) }
            layoutParams = lp
            setOnClickListener {
                val res = com.example.autoclicker.engine.RunCoordinator.start(applicationContext)
                if (res.anyStarted) {
                    setMode(minimized = true)
                }
            }
        }

        stopBtn = NeonGlassButton(this).apply {
            text = "STOP"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            cornerDp = 16f
            hueOffset = -110f
            isEnabled = false
            alpha = 0.5f
            val lp = LinearLayout.LayoutParams(0, dpToPx(44), 1f).apply { marginStart = dpToPx(6) }
            layoutParams = lp
            setOnClickListener {
                com.example.autoclicker.engine.RunCoordinator.stopAll(applicationContext, "кнопка Стоп")
                visualOverlay?.cancelAnimations()
            }
        }

        controlRow.addView(startBtn)
        controlRow.addView(stopBtn)
        expandedLayout.addView(controlRow)

        // 3.1. Блок «Что запускать»
        val runGroupsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dpToPx(8))
        }

        btnRunPoints = NeonGlassButton(this).apply {
            text = "Точки"
            textSize = 11f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            cornerDp = 14f
            val lp = LinearLayout.LayoutParams(0, dpToPx(38), 1f).apply { marginEnd = dpToPx(3) }
            layoutParams = lp
            setOnClickListener {
                val s = settingsRepo.getLatestSettings()
                settingsRepo.updateRunPoints(!s.runPoints)
            }
        }

        btnRunSwipes = NeonGlassButton(this).apply {
            text = "Свайпы"
            textSize = 11f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            cornerDp = 14f
            val lp = LinearLayout.LayoutParams(0, dpToPx(38), 1f).apply {
                marginStart = dpToPx(2)
                marginEnd = dpToPx(2)
            }
            layoutParams = lp
            setOnClickListener {
                val s = settingsRepo.getLatestSettings()
                settingsRepo.updateRunSwipes(!s.runSwipes)
            }
        }

        btnRunSmart = NeonGlassButton(this).apply {
            text = "Умный"
            textSize = 11f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            cornerDp = 14f
            val lp = LinearLayout.LayoutParams(0, dpToPx(38), 1f).apply { marginStart = dpToPx(3) }
            layoutParams = lp
            setOnClickListener {
                val s = settingsRepo.getLatestSettings()
                settingsRepo.updateRunSmart(!s.isSmartMode)
            }
        }

        runGroupsRow.addView(btnRunPoints)
        runGroupsRow.addView(btnRunSwipes)
        runGroupsRow.addView(btnRunSmart)
        expandedLayout.addView(runGroupsRow)

        // 4. Ряд из трёх стеклянных кнопок: «Точки», «Свайпы», «Умный режим» + кнопка «Ещё»
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        btnPoints = createNavButton("Точки", 1.2f) {
            togglePopupWindow(SettingsPopupWindow.WindowType.POINTS)
        }
        navRow.addView(btnPoints)

        btnSwipes = createNavButton("Свайпы", 1.2f) {
            togglePopupWindow(SettingsPopupWindow.WindowType.SWIPES)
        }
        navRow.addView(btnSwipes)

        btnSmart = createNavButton("Умный", 1.2f) {
            togglePopupWindow(SettingsPopupWindow.WindowType.SMART)
        }
        navRow.addView(btnSmart)

        btnMore = createNavButton("Ещё", 0.9f) {
            togglePopupWindow(SettingsPopupWindow.WindowType.MORE)
        }
        navRow.addView(btnMore)

        expandedLayout.addView(navRow)
    }

    private fun createNavButton(label: String, weight: Float, onClick: () -> Unit): Button {
        return NeonGlassButton(this).apply {
            text = label
            textSize = 11f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            cornerDp = 16f
            hueOffset = when (label) { "Точки" -> 0f; "Свайпы" -> 15f; "Умный" -> 30f; else -> 45f }
            val lp = LinearLayout.LayoutParams(0, dpToPx(42), weight).apply {
                marginEnd = dpToPx(3)
            }
            layoutParams = lp
            setPadding(dpToPx(2), 0, dpToPx(2), 0)
            setOnClickListener { onClick() }
        }
    }

    private fun togglePopupWindow(type: SettingsPopupWindow.WindowType) {
        if (popupWindow.isShowing() && popupWindow.currentType == type) {
            popupWindow.dismiss()
        } else {
            val p = overlayParams ?: return
            val w = expandedLayout.width.takeIf { it > 0 } ?: dpToPx(280)
            val h = expandedLayout.height.takeIf { it > 0 } ?: dpToPx(200)
            popupWindow.show(type, p.x, p.y, w, h)
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

    private fun checkOverlapWarning() {
        val params = overlayParams ?: return
        val panelW = expandedLayout.width.takeIf { it > 0 } ?: dpToPx(280)
        val panelH = expandedLayout.height.takeIf { it > 0 } ?: dpToPx(220)
        val panelRect = Rect(params.x, params.y, params.x + panelW, params.y + panelH)

        val settings = settingsRepo.getLatestSettings()
        val overlapping = mutableListOf<String>()
        for (p in settings.allPoints) {
            if (p.enabled && p.isConfigured && panelRect.contains(p.x.toInt(), p.y.toInt())) {
                overlapping.add("P${p.id}")
            }
        }

        if (overlapping.isNotEmpty()) {
            overlapWarningText.visibility = View.VISIBLE
            overlapWarningText.text = "⚠ Внимание: ${overlapping.joinToString(", ")} перекрыта панелью!"
        } else {
            overlapWarningText.visibility = View.GONE
        }
    }

    // Callbacks from SettingsPopupWindow
    override fun onStartPointCalibration(pointId: Int) {
        rootContainer?.visibility = View.GONE
        popupWindow.dismiss()
        val old = activeCalibrationView
        activeCalibrationView = null
        old?.dismiss()

        lateinit var overlay: CalibrationOverlayView
        overlay = CalibrationOverlayView(
            context = this,
            currentPointId = pointId,
            onCoordinateCaptured = { _, _, _ -> },
            onDismissed = {
                if (activeCalibrationView === overlay) {
                    activeCalibrationView = null
                    rootContainer?.visibility = View.VISIBLE
                    val p = overlayParams
                    val w = expandedLayout.width.takeIf { it > 0 } ?: dpToPx(280)
                    val h = expandedLayout.height.takeIf { it > 0 } ?: dpToPx(200)
                    popupWindow.show(
                        SettingsPopupWindow.WindowType.POINTS,
                        p?.x ?: 100,
                        p?.y ?: 100,
                        w,
                        h
                    )
                }
            }
        )
        activeCalibrationView = overlay
        overlay.show()
    }

    override fun onStartSwipeCalibration(swipeId: Int, isStart: Boolean) {
        rootContainer?.visibility = View.GONE
        popupWindow.dismiss()
        val old = activeCalibrationView
        activeCalibrationView = null
        old?.dismiss()

        val pseudoPointId = if (isStart) 10 + (swipeId * 2 - 1) else 10 + (swipeId * 2)
        lateinit var overlay: CalibrationOverlayView
        overlay = CalibrationOverlayView(
            context = this,
            currentPointId = pseudoPointId,
            onCoordinateCaptured = { _, x, y ->
                val settings = settingsRepo.getLatestSettings()
                val currentSwipe = settings.swipes.find { it.id == swipeId } ?: SwipeAction(id = swipeId)
                if (isStart) {
                    settingsRepo.updateSwipeCoordinates(swipeId, x, y, currentSwipe.endX, currentSwipe.endY)
                    onStartSwipeCalibration(swipeId, false)
                } else {
                    settingsRepo.updateSwipeCoordinates(swipeId, currentSwipe.startX, currentSwipe.startY, x, y)
                }
            },
            onDismissed = {
                if (activeCalibrationView === overlay) {
                    activeCalibrationView = null
                    rootContainer?.visibility = View.VISIBLE
                    val p = overlayParams
                    val w = expandedLayout.width.takeIf { it > 0 } ?: dpToPx(280)
                    val h = expandedLayout.height.takeIf { it > 0 } ?: dpToPx(200)
                    popupWindow.show(
                        SettingsPopupWindow.WindowType.SWIPES,
                        p?.x ?: 100,
                        p?.y ?: 100,
                        w,
                        h
                    )
                }
            }
        )
        activeCalibrationView = overlay
        overlay.show()
    }

    override fun onOpenMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    override fun onToggleVisualOverlay() {
        val overlay = visualOverlay ?: return
        overlay.showPoints = !overlay.showPoints
    }

    override fun onCloseService() {
        shutdownAllWindowsAndServices()
        AutoClickForegroundService.stop(applicationContext)
    }

    override fun onDismiss() {}

    private fun observeSettingsChanges() {
        serviceScope.launch {
            settingsRepo.settings.collect { settings ->
                visualOverlay?.updateSettings(settings)
                popupWindow.updateSettings(settings)
                checkOverlapWarning()
                btnRunPoints.isOn = settings.runPoints
                btnRunPoints.text = if (settings.runPoints) "✔ Точки" else "Точки"
                btnRunSwipes.isOn = settings.runSwipes
                btnRunSwipes.text = if (settings.runSwipes) "✔ Свайпы" else "Свайпы"
                btnRunSmart.isOn = settings.isSmartMode
                btnRunSmart.text = if (settings.isSmartMode) "✔ Умный" else "Умный"
            }
        }
    }

    private fun observeControllerState() {
        serviceScope.launch {
            kotlinx.coroutines.flow.combine(
                cycleController.status,
                smartEngine.status,
                swipeController.isRunning,
                macroController.isRunning
            ) { cStatus: CycleStatus, sStatus: CycleStatus, swipeRunning: Boolean, macroRunning: Boolean ->
                val isSmartActive = sStatus != CycleStatus.STOPPED
                val isCycleActive = cStatus != CycleStatus.STOPPED
                val status = when {
                    isSmartActive -> sStatus
                    isCycleActive -> cStatus
                    swipeRunning || macroRunning -> CycleStatus.RUNNING
                    else -> CycleStatus.STOPPED
                }
                val mode = when {
                    macroRunning -> "MACRO"
                    swipeRunning && !isSmartActive && !isCycleActive -> "SWIPES"
                    isSmartActive -> "SMART"
                    else -> "NORMAL"
                }
                Pair(status, mode)
            }.collect { (status, mode) ->
                val isSmartActive = mode == "SMART"
                val cycleNum = if (isSmartActive) smartEngine.cycleNumber.value else cycleController.cycleNumber.value
                val statusString = when (status) {
                    CycleStatus.STOPPED -> "STATUS: STOPPED"
                    CycleStatus.RUNNING -> when (mode) {
                        "SMART" -> "STATUS: SMART RUNNING"
                        "SWIPES" -> "STATUS: SWIPES RUNNING"
                        "MACRO" -> "STATUS: MACRO RUNNING"
                        else -> if (cycleNum > 0) "STATUS: RUNNING (#$cycleNum)" else "STATUS: RUNNING"
                    }
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
                    startBtn.isOn = true
                    startBtn.isEnabled = false
                    startBtn.alpha = 1.0f

                    stopBtn.isEnabled = true
                    stopBtn.alpha = 1.0f
                    stopBtn.isOn = true

                    floatingBadgeButton.text = "■"
                    floatingBadgeButton.setTextColor(Color.parseColor("#FF5252"))
                } else {
                    startBtn.text = "START"
                    startBtn.isOn = false
                    startBtn.isEnabled = true
                    startBtn.alpha = 1.0f

                    stopBtn.isEnabled = false
                    stopBtn.alpha = 0.5f
                    stopBtn.isOn = false

                    floatingBadgeButton.text = "▶"
                    floatingBadgeButton.setTextColor(Color.parseColor("#00E676"))
                }
            }
        }

        serviceScope.launch {
            kotlinx.coroutines.flow.combine(
                cycleController.nextAction,
                smartEngine.nextAction,
                smartEngine.status
            ) { cNext, sNext, sStatus ->
                if (sStatus != CycleStatus.STOPPED) sNext else cNext
            }.collect { action ->
                nextActionText.text = "NEXT: $action"
            }
        }

        serviceScope.launch {
            kotlinx.coroutines.flow.combine(
                cycleController.countdownText,
                smartEngine.countdownText,
                smartEngine.status,
                smartEngine.currentAction
            ) { cCd, sCd, sStatus, sAction ->
                if (sStatus != CycleStatus.STOPPED) {
                    if (sCd.isNotEmpty()) "TIMER: $sCd" else "SMART: $sAction"
                } else {
                    if (cCd.isNotEmpty()) "TIMER: $cCd" else "TIMER: --"
                }
            }.collect { cdText ->
                timerText.text = cdText
            }
        }
    }

    // TapHooks
    override suspend fun beforeTap(pointId: Int, x: Float, y: Float, tapIndex: Int, totalTaps: Int) {
        val loc = IntArray(2)
        rootContainer?.getLocationOnScreen(loc)
        val panelW = expandedLayout.width.takeIf { it > 0 } ?: dpToPx(280)
        val panelH = expandedLayout.height.takeIf { it > 0 } ?: dpToPx(200)
        val panelRect = Rect(loc[0], loc[1], loc[0] + panelW, loc[1] + panelH)

        if (panelRect.contains(x.toInt(), y.toInt())) {
            rootContainer?.visibility = View.GONE
        }
    }

    override suspend fun afterTap(pointId: Int, x: Float, y: Float, success: Boolean, tapIndex: Int, totalTaps: Int) {
        rootContainer?.visibility = View.VISIBLE
        visualOverlay?.showTapRipple(pointId, x, y, success, tapIndex, totalTaps)
        val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val statusStr = if (success) "OK" else "FAIL"
        lastTapText.text = "$timeStr P$pointId (${x.toInt()}, ${y.toInt()}): $statusStr ($tapIndex/$totalTaps)"
        lastTapText.setTextColor(if (success) Color.parseColor("#00E676") else Color.parseColor("#FF5252"))
    }

    private fun createPanelBg(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(20).toFloat()
            colors = intArrayOf(
                Color.parseColor("#EE141428"),
                Color.parseColor("#FA0A0A14")
            )
            setStroke(dpToPx(2), NeonTheme.getBorderColorInt())
        }
    }

    private fun createBadgeBg(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            colors = intArrayOf(
                Color.parseColor("#EE141428"),
                Color.parseColor("#FA0A0A14")
            )
            setStroke(dpToPx(2), NeonTheme.getBorderColorInt())
        }
    }

    private fun createButtonBg(color: Int, radiusPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(color)
        }
    }

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

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun shutdownAllWindowsAndServices() {
        neonAnimator?.cancel()
        popupWindow.dismiss()
        activeCalibrationView?.dismiss()
        activeCalibrationView = null
        CalibrationOverlayView.dismissActive()
        MacroRecordingOverlayView.dismissActive()
        visualOverlay?.detachFromWindow()
        visualOverlay = null
        rootContainer?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        rootContainer = null
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        neonAnimator?.cancel()
        serviceScope.cancel()
        popupWindow.dismiss()
        activeCalibrationView?.dismiss()
        activeCalibrationView = null
        CalibrationOverlayView.dismissActive()
        MacroRecordingOverlayView.dismissActive()
        visualOverlay?.detachFromWindow()
        rootContainer?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
