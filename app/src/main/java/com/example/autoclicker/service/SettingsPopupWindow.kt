package com.example.autoclicker.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
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
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.example.autoclicker.data.ClickPoint
import com.example.autoclicker.data.ClickerSettings
import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.data.SmartConfigManager
import com.example.autoclicker.data.SmartConfigMode
import com.example.autoclicker.data.SwipeAction
import com.example.autoclicker.engine.CycleController
import com.example.autoclicker.engine.MacroController
import com.example.autoclicker.engine.SmartEngine
import com.example.autoclicker.engine.SwipeController
import com.example.autoclicker.ui.theme.NeonTheme
import kotlin.math.abs

/**
 * Отдельное плавающее окно настроек (TYPE_APPLICATION_OVERLAY).
 * Перетаскивается за заголовок, содержит кнопку «✕».
 * Ширина не более 70% экрана, располагается рядом с основной панелью.
 */
@SuppressLint("ClickableViewAccessibility")
class SettingsPopupWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    private val settingsRepo: SettingsRepository,
    private val configManager: SmartConfigManager,
    private val cycleController: CycleController,
    private val smartEngine: SmartEngine,
    private val swipeController: SwipeController,
    private val macroController: MacroController,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onStartPointCalibration(pointId: Int)
        fun onStartSwipeCalibration(swipeId: Int, isStart: Boolean)
        fun onOpenMainActivity()
        fun onToggleVisualOverlay()
        fun onCloseService()
        fun onDismiss()
    }

    enum class WindowType {
        POINTS, SWIPES, SMART, MORE
    }

    var currentType: WindowType? = null
        private set

    private var rootLayout: LinearLayout? = null
    private var contentContainer: LinearLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var isShown = false
    private val density = context.resources.displayMetrics.density

    private fun dp(value: Int): Int = (value * density).toInt()

    fun isShowing(): Boolean = isShown

    fun show(type: WindowType, anchorX: Int, anchorY: Int, anchorWidth: Int, anchorHeight: Int) {
        if (isShown) {
            dismiss()
        }
        currentType = type

        val (screenWidth, screenHeight) = getScreenSize()
        val windowWidth = (minOf(screenWidth, screenHeight) * 0.70f).toInt().coerceAtLeast(dp(220))
        val maxWindowHeight = (screenHeight * 0.65f).toInt()

        // Позиционирование рядом с панелью (слева или справа)
        val initialX = if (anchorX + anchorWidth + windowWidth + dp(12) < screenWidth) {
            anchorX + anchorWidth + dp(8)
        } else {
            (anchorX - windowWidth - dp(8)).coerceAtLeast(dp(8))
        }
        val initialY = anchorY.coerceIn(dp(24), screenHeight - maxWindowHeight - dp(24))

        val params = WindowManager.LayoutParams(
            windowWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }
        windowParams = params

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = createGlassBg(dp(20))
            setPadding(dp(12), dp(10), dp(12), dp(12))
            isClickable = true
        }
        rootLayout = root

        // Заголовок окна с перетаскиванием и кнопкой ✕
        val header = createHeader(type)
        root.addView(header)

        // Контейнер содержимого
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        contentContainer = content

        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                weight = 1f
            }
            isVerticalScrollBarEnabled = true
            addView(content)
        }
        root.addView(scrollView)

        // Заполнение контента по типу
        populateContent(type)

        try {
            windowManager.addView(root, params)
            isShown = true
        } catch (e: Exception) {
            EventLogManager.log(
                EventLogManager.TAG_OVERLAY,
                "ERROR: не удалось открыть popup: ${e.message}",
                isError = true
            )
            isShown = false
        }
    }

    private fun createHeader(type: WindowType): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(8))

            val titleText = when (type) {
                WindowType.POINTS -> "Точки"
                WindowType.SWIPES -> "Свайпы"
                WindowType.SMART -> "Умный режим"
                WindowType.MORE -> "Ещё"
            }

            val title = TextView(context).apply {
                text = titleText
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(title)

            // Кнопка ✕
            val closeBtn = Button(context).apply {
                text = "✕"
                textSize = 12f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                background = createButtonBg(Color.parseColor("#33FFFFFF"), dp(14))
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                setOnClickListener { dismiss() }
            }
            addView(closeBtn)

            // Перетаскивание за заголовок
            setupDrag(this)
        }
    }

    private fun setupDrag(view: View) {
        var startX = 0
        var startY = 0
        var touchStartX = 0f
        var touchStartY = 0f

        view.setOnTouchListener { _, event ->
            val p = windowParams ?: return@setOnTouchListener false
            val root = rootLayout ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x
                    startY = p.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchStartX).toInt()
                    val dy = (event.rawY - touchStartY).toInt()
                    p.x = startX + dx
                    p.y = startY + dy
                    try {
                        windowManager.updateViewLayout(root, p)
                    } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }
    }

    fun updateSettings(settings: ClickerSettings) {
        if (!isShown) return
        val type = currentType ?: return
        contentContainer?.let {
            it.removeAllViews()
            populateContent(type)
        }
    }

    fun updateNeonColor() {
        if (!isShown) return
        val root = rootLayout ?: return
        val gd = root.background as? GradientDrawable ?: return
        gd.setStroke(dp(2), NeonTheme.getBorderColorInt())
    }

    private fun populateContent(type: WindowType) {
        val container = contentContainer ?: return
        container.removeAllViews()

        when (type) {
            WindowType.POINTS -> populatePoints(container)
            WindowType.SWIPES -> populateSwipes(container)
            WindowType.SMART -> populateSmartMode(container)
            WindowType.MORE -> populateMore(container)
        }
    }

    // ===== 4. ОКНО «ТОЧКИ» =====
    private fun populatePoints(container: LinearLayout) {
        val settings = settingsRepo.getLatestSettings()
        val activePoints = settings.allPoints.filter { it.enabled }

        if (activePoints.isEmpty()) {
            val emptyView = TextView(context).apply {
                text = "Нет активных точек.\nВключите нужные точки в приложении."
                setTextColor(Color.parseColor("#B0BEC5"))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(16), dp(8), dp(16))
            }
            container.addView(emptyView)
            return
        }

        for (pt in activePoints) {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = createCardBg()
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }

                // Заголовок и координаты
                val infoRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val title = TextView(context).apply {
                    text = "Точка ${pt.id}"
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                infoRow.addView(title)

                val coords = TextView(context).apply {
                    text = if (pt.isConfigured) "X=${pt.x.toInt()}  Y=${pt.y.toInt()}" else "не задана"
                    setTextColor(if (pt.isConfigured) Color.parseColor("#8CFF00") else Color.parseColor("#FF5252"))
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                }
                infoRow.addView(coords)
                addView(infoRow)

                // Кнопки «Применить» и «Тест»
                val btnRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(6) }

                    val applyBtn = Button(context).apply {
                        text = "Применить"
                        textSize = 11f
                        setTextColor(Color.WHITE)
                        typeface = Typeface.DEFAULT_BOLD
                        background = createButtonBg(Color.parseColor("#2E7D32"), dp(8))
                        layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(6) }
                        setOnClickListener {
                            callbacks.onStartPointCalibration(pt.id)
                        }
                    }
                    addView(applyBtn)

                    val testBtn = Button(context).apply {
                        text = "Тест"
                        textSize = 11f
                        setTextColor(Color.WHITE)
                        typeface = Typeface.DEFAULT_BOLD
                        background = createButtonBg(Color.parseColor("#1565C0"), dp(8))
                        layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f)
                        isEnabled = pt.isConfigured
                        setOnClickListener {
                            cycleController.testClick(pt.id)
                        }
                    }
                    addView(testBtn)
                }
                addView(btnRow)
            }
            container.addView(card)
        }
    }

    // ===== 5. ОКНО «СВАЙПЫ» =====
    private fun populateSwipes(container: LinearLayout) {
        val settings = settingsRepo.getLatestSettings()
        val activeSwipes = settings.swipes.filter { it.enabled }

        if (activeSwipes.isEmpty()) {
            val emptyView = TextView(context).apply {
                text = "Нет активных свайпов.\nВключите нужные свайпы в приложении."
                setTextColor(Color.parseColor("#B0BEC5"))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(16), dp(8), dp(16))
            }
            container.addView(emptyView)
            return
        }

        for (sw in activeSwipes) {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = createCardBg()
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }

                // Заголовок и координаты
                val infoRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val title = TextView(context).apply {
                    text = "Свайп ${sw.id}"
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                infoRow.addView(title)

                val coords = TextView(context).apply {
                    text = if (sw.isConfigured) {
                        "(${sw.startX.toInt()},${sw.startY.toInt()})→(${sw.endX.toInt()},${sw.endY.toInt()})"
                    } else {
                        "не задан"
                    }
                    setTextColor(if (sw.isConfigured) Color.parseColor("#8CFF00") else Color.parseColor("#FF5252"))
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                }
                infoRow.addView(coords)
                addView(infoRow)

                // Кнопки «Применить» и «Тест»
                val btnRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(6) }

                    val applyBtn = Button(context).apply {
                        text = "Применить"
                        textSize = 11f
                        setTextColor(Color.WHITE)
                        typeface = Typeface.DEFAULT_BOLD
                        background = createButtonBg(Color.parseColor("#2E7D32"), dp(8))
                        layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(6) }
                        setOnClickListener {
                            callbacks.onStartSwipeCalibration(sw.id, true)
                        }
                    }
                    addView(applyBtn)

                    val testBtn = Button(context).apply {
                        text = "Тест"
                        textSize = 11f
                        setTextColor(Color.WHITE)
                        typeface = Typeface.DEFAULT_BOLD
                        background = createButtonBg(Color.parseColor("#1565C0"), dp(8))
                        layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f)
                        isEnabled = sw.isConfigured
                        setOnClickListener {
                            swipeController.testSwipe(sw.id)
                        }
                    }
                    addView(testBtn)
                }
                addView(btnRow)
            }
            container.addView(card)
        }
    }

    // ===== 6. ОКНО «УМНЫЙ РЕЖИМ» =====
    private fun populateSmartMode(container: LinearLayout) {
        val settings = settingsRepo.getLatestSettings()
        val isApi30 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        // 1. Тумблер «Умный режим»
        val smartRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))

            val label = TextView(context).apply {
                text = "Умный режим:"
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(label)

            val sw = Switch(context).apply {
                isChecked = settings.isSmartMode && isApi30
                isEnabled = isApi30
                setOnCheckedChangeListener { _, isChecked ->
                    settingsRepo.updateSmartMode(isChecked)
                }
            }
            addView(sw)
        }
        container.addView(smartRow)

        // 2. Тумблер «Свой конфиг»
        val currentMode = configManager.getMode()
        val isCustomActive = configManager.getActiveName().isNotEmpty() && currentMode != SmartConfigMode.DEFAULT_ONLY

        val customRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))

            val label = TextView(context).apply {
                text = "Свой конфиг:"
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(label)

            val sw = Switch(context).apply {
                isChecked = isCustomActive
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        val available = configManager.listConfigs()
                        val target = if (configManager.getActiveName().isNotEmpty()) {
                            configManager.getActiveName()
                        } else {
                            available.lastOrNull() ?: ""
                        }
                        if (target.isNotEmpty()) {
                            configManager.setActiveName(target)
                            configManager.setMode(SmartConfigMode.CUSTOM_ONLY)
                        } else {
                            configManager.setMode(SmartConfigMode.CUSTOM_ONLY)
                        }
                    } else {
                        configManager.setActiveName("")
                        configManager.setMode(SmartConfigMode.DEFAULT_ONLY)
                    }
                    populateContent(WindowType.SMART)
                }
            }
            addView(sw)
        }
        container.addView(customRow)

        // Разделитель
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
        }
        container.addView(divider)

        // Заголовок списка конфигов
        val listTitle = TextView(context).apply {
            text = "ВЫБОР КОНФИГА:"
            setTextColor(Color.parseColor("#90CAF9"))
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(6), dp(2), dp(6), dp(4))
        }
        container.addView(listTitle)

        // Запись «по умолчанию (PUBG)»
        val isDefaultActive = configManager.getActiveName().isEmpty() || currentMode == SmartConfigMode.DEFAULT_ONLY
        val defaultItem = createConfigItemView("по умолчанию (PUBG)", isDefaultActive) {
            configManager.setActiveName("")
            configManager.setMode(SmartConfigMode.DEFAULT_ONLY)
            populateContent(WindowType.SMART)
        }
        container.addView(defaultItem)

        // Пользовательские конфиги
        val userConfigs = configManager.listConfigs()
        if (userConfigs.isEmpty()) {
            val emptyConfigs = TextView(context).apply {
                text = "Конфиги не загружены.\nЗагрузите zip в приложении (Меню)."
                setTextColor(Color.parseColor("#B0BEC5"))
                textSize = 11f
                setPadding(dp(6), dp(8), dp(6), dp(8))
            }
            container.addView(emptyConfigs)
        } else {
            for (cfg in userConfigs) {
                val isActive = configManager.getActiveName() == cfg && currentMode != SmartConfigMode.DEFAULT_ONLY
                val item = createConfigItemView(cfg, isActive) {
                    configManager.setActiveName(cfg)
                    configManager.setMode(SmartConfigMode.CUSTOM_ONLY)
                    populateContent(WindowType.SMART)
                }
                container.addView(item)
            }
        }
    }

    private fun createConfigItemView(name: String, isSelected: Boolean, onClick: () -> Unit): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = createButtonBg(
                if (isSelected) Color.parseColor("#3300E5FF") else Color.parseColor("#1A212D"),
                dp(8)
            )
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
            isClickable = true
            setOnClickListener { onClick() }

            val label = TextView(context).apply {
                text = if (isSelected) "✔ $name" else name
                setTextColor(if (isSelected) Color.parseColor("#00E5FF") else Color.WHITE)
                textSize = 11f
                typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(label)
        }
    }

    // ===== 2. ОКНО «ЕЩЁ» (ТАЙМЕР, МАКРОС, ЖУРНАЛ, НАСТРОЙКИ, СКРЫТЬ) =====
    private fun populateMore(container: LinearLayout) {
        val settings = settingsRepo.getLatestSettings()

        // 1. Таймер цикла со степпером «−» / «+»
        val timerCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = createCardBg()
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val tLabel = TextView(context).apply {
                text = "Таймер цикла:"
                setTextColor(Color.WHITE)
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(tLabel)

            val minusBtn = Button(context).apply {
                text = "−"
                textSize = 13f
                setTextColor(Color.WHITE)
                background = createButtonBg(Color.parseColor("#37474F"), dp(6))
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
                setOnClickListener {
                    val cur = settingsRepo.getLatestSettings().cycleDelayMinutes
                    if (cur > 1) settingsRepo.updateCycleDelay(cur - 1)
                    populateContent(WindowType.MORE)
                }
            }
            row.addView(minusBtn)

            val valText = TextView(context).apply {
                text = "${settings.cycleDelayMinutes}м"
                setTextColor(Color.parseColor("#8CFF00"))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(8), 0, dp(8), 0)
            }
            row.addView(valText)

            val plusBtn = Button(context).apply {
                text = "+"
                textSize = 13f
                setTextColor(Color.WHITE)
                background = createButtonBg(Color.parseColor("#37474F"), dp(6))
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
                setOnClickListener {
                    val cur = settingsRepo.getLatestSettings().cycleDelayMinutes
                    if (cur < 15) settingsRepo.updateCycleDelay(cur + 1)
                    populateContent(WindowType.MORE)
                }
            }
            row.addView(plusBtn)

            addView(row)
        }
        container.addView(timerCard)

        // 2. Блок «Макрос»
        val macroCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = createCardBg()
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }

            val mTitle = TextView(context).apply {
                text = "Макрос:"
                setTextColor(Color.WHITE)
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
            }
            addView(mTitle)

            val hasMacro = settings.recordedMacro != null && settings.recordedMacro.isNotEmpty
            val mStatus = TextView(context).apply {
                text = if (macroController.isRunning.value) {
                    "▶ Выполняется"
                } else if (hasMacro) {
                    "Записан (${settings.recordedMacro?.formattedDuration})"
                } else {
                    "Не записан"
                }
                setTextColor(if (hasMacro) Color.parseColor("#8CFF00") else Color.parseColor("#B0BEC5"))
                textSize = 10f
                setPadding(0, dp(2), 0, dp(4))
            }
            addView(mStatus)

            val mBtns = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )

                val playBtn = Button(context).apply {
                    val isRunning = macroController.isRunning.value
                    text = if (isRunning) "■ Стоп" else "▶ Старт"
                    textSize = 10f
                    setTextColor(Color.WHITE)
                    background = createButtonBg(
                        if (isRunning) Color.parseColor("#C62828") else Color.parseColor("#2E7D32"),
                        dp(6)
                    )
                    layoutParams = LinearLayout.LayoutParams(0, dp(32), 1f).apply { marginEnd = dp(4) }
                    isEnabled = hasMacro || isRunning
                    setOnClickListener {
                        if (macroController.isRunning.value) macroController.stop()
                        else macroController.start(settings.macroRepeatCount, settings.macroIntervalSec)
                        populateContent(WindowType.MORE)
                    }
                }
                addView(playBtn)

                val clearBtn = Button(context).apply {
                    text = "Очистить"
                    textSize = 10f
                    setTextColor(Color.WHITE)
                    background = createButtonBg(Color.parseColor("#37474F"), dp(6))
                    layoutParams = LinearLayout.LayoutParams(0, dp(32), 1f)
                    isEnabled = hasMacro
                    setOnClickListener {
                        settingsRepo.clearMacro()
                        populateContent(WindowType.MORE)
                    }
                }
                addView(clearBtn)
            }
            addView(mBtns)
        }
        container.addView(macroCard)

        // 3. Кнопка «Показать/скрыть метки точек»
        val toggleMarksBtn = Button(context).apply {
            text = "Метки точек на экране"
            textSize = 11f
            setTextColor(Color.WHITE)
            background = createButtonBg(Color.parseColor("#263238"), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(34)
            ).apply { bottomMargin = dp(6) }
            setOnClickListener {
                callbacks.onToggleVisualOverlay()
            }
        }
        container.addView(toggleMarksBtn)

        // 4. Кнопка «Открыть приложение»
        val openAppBtn = Button(context).apply {
            text = "Открыть приложение"
            textSize = 11f
            setTextColor(Color.WHITE)
            background = createButtonBg(Color.parseColor("#1565C0"), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(34)
            ).apply { bottomMargin = dp(6) }
            setOnClickListener {
                callbacks.onOpenMainActivity()
                dismiss()
            }
        }
        container.addView(openAppBtn)

        // 5. Кнопка «Закрыть плавающее окно»
        val closeServiceBtn = Button(context).apply {
            text = "Закрыть плавающее окно"
            textSize = 11f
            setTextColor(Color.parseColor("#FF8A80"))
            background = createButtonBg(Color.parseColor("#3E1B1B"), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(34)
            )
            setOnClickListener {
                callbacks.onCloseService()
            }
        }
        container.addView(closeServiceBtn)
    }

    fun dismiss() {
        if (!isShown) return
        isShown = false
        currentType = null
        rootLayout?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        rootLayout = null
        contentContainer = null
        windowParams = null
        callbacks.onDismiss()
    }

    private fun createGlassBg(radiusPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            colors = intArrayOf(
                Color.parseColor("#EE141428"),
                Color.parseColor("#FA0A0A14")
            )
            setStroke(dp(2), NeonTheme.getBorderColorInt())
        }
    }

    private fun createCardBg(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(Color.parseColor("#33FFFFFF"))
            setStroke(dp(1), Color.parseColor("#26FFFFFF"))
        }
    }

    private fun createButtonBg(color: Int, radiusPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(color)
        }
    }

    private fun getScreenSize(): Pair<Int, Int> {
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
}
