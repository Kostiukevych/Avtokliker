package com.example.autoclicker.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.autoclicker.data.ClickerSettings
import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.data.SmartConfigManager
import com.example.autoclicker.data.SmartConfigMode
import com.example.autoclicker.engine.CycleController
import com.example.autoclicker.engine.MacroController
import com.example.autoclicker.engine.SmartEngine
import com.example.autoclicker.engine.SwipeController
import com.example.autoclicker.ui.theme.NeonTheme

/**
 * Отдельное плавающее окно настроек (TYPE_APPLICATION_OVERLAY) в стиле liquid-glass-neon.
 * Перетаскивается за заголовок, содержит кнопку «✕». Ширина не более 70% экрана.
 * Окна: Точки, Свайпы, Умный режим, Ещё (таймер, яркость и цвет неона, макрос, метки, закрыть).
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

    // Слайдеры, которые сейчас может тянуть палец: пока тянем, окно не перерисовываем
    private val sliders = mutableListOf<NeonGlassSlider>()

    private fun dp(value: Int): Int = (value * density).toInt()

    fun isShowing(): Boolean = isShown

    fun show(type: WindowType, anchorX: Int, anchorY: Int, anchorWidth: Int, anchorHeight: Int) {
        if (isShown) {
            dismiss()
        }
        currentType = type

        val (screenWidth, screenHeight) = getScreenSize()
        val windowWidth = (minOf(screenWidth, screenHeight) * 0.70f).toInt().coerceAtLeast(dp(240))
        val maxWindowHeight = (screenHeight * 0.65f).toInt()

        val initialX = if (anchorX + anchorWidth + windowWidth + dp(12) < screenWidth) {
            anchorX + anchorWidth + dp(8)
        } else {
            (anchorX - windowWidth - dp(8)).coerceAtLeast(dp(8))
        }
        val initialY = anchorY.coerceIn(dp(24), (screenHeight - maxWindowHeight - dp(24)).coerceAtLeast(dp(24)))

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

        val root = NeonPanel(context, 24f).apply {
            setPadding(dp(14), dp(12), dp(14), dp(14))
            isClickable = true
        }
        rootLayout = root

        root.addView(createHeader(type))

        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        contentContainer = content

        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                minOf(maxWindowHeight, dp(420))
            )
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content)
        }
        root.addView(scrollView)

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
            setPadding(dp(4), dp(2), dp(2), dp(8))

            val titleText = when (type) {
                WindowType.POINTS -> "Точки"
                WindowType.SWIPES -> "Свайпы"
                WindowType.SMART -> "Умный режим"
                WindowType.MORE -> "Ещё"
            }

            addView(TextView(context).apply {
                text = titleText
                setTextColor(0xFFF1F4FB.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            addView(NeonGlassButton(context).apply {
                text = "✕"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                cornerDp = 12f
                hueOffset = -60f
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
                setOnClickListener { dismiss() }
            })

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
                    p.x = startX + (event.rawX - touchStartX).toInt()
                    p.y = startY + (event.rawY - touchStartY).toInt()
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
        // Не пересоздаём окно, пока палец на слайдере (иначе слайдер «вылетит» из-под пальца)
        if (sliders.any { it.dragging }) return
        populateContent(type)
    }

    fun updateNeonColor() {
        if (!isShown) return
        rootLayout?.invalidate()
    }

    private fun populateContent(type: WindowType) {
        val container = contentContainer ?: return
        container.removeAllViews()
        sliders.clear()

        when (type) {
            WindowType.POINTS -> populatePoints(container)
            WindowType.SWIPES -> populateSwipes(container)
            WindowType.SMART -> populateSmartMode(container)
            WindowType.MORE -> populateMore(container)
        }
    }

    // ---------- Вспомогательные фабрики ----------

    private fun card(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            setColor(0x2214182C)
            setStroke(dp(1), 0x26FFFFFF)
        }
        setPadding(dp(10), dp(8), dp(10), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }
    }

    private fun infoRow(left: String, right: String, rightColor: Int, rightSp: Float = 11f): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = left
                setTextColor(0xFFF1F4FB.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply {
                text = right
                setTextColor(rightColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, rightSp)
                typeface = Typeface.DEFAULT_BOLD
            })
        }

    private fun twoButtons(left: NeonGlassButton, right: NeonGlassButton): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            left.layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(6) }
            right.layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
            addView(left)
            addView(right)
        }

    private fun fullButton(btn: NeonGlassButton, heightDp: Int = 44, bottomDp: Int = 8): NeonGlassButton = btn.apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp)
        ).apply { bottomMargin = dp(bottomDp) }
    }

    private fun emptyText(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(0xFFB0BEC5.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(16), dp(8), dp(16))
    }

    private fun toggle(text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit): NeonGlassToggle =
        NeonGlassToggle(context).apply {
            this.text = text
            setCheckedSilently(checked)
            isEnabled = enabled
            onCheckedChange = onChange
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            ).apply { bottomMargin = dp(8) }
        }

    private fun slider(min: Int, max: Int, value: Int, fmt: (Int) -> String, onChange: (Int) -> Unit): NeonGlassSlider =
        NeonGlassSlider(context).apply {
            this.min = min
            this.max = max
            this.value = value
            formatter = fmt
            this.onChange = onChange
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            ).apply { bottomMargin = dp(8) }
            sliders.add(this)
        }

    // ===== «ТОЧКИ» =====
    private fun populatePoints(container: LinearLayout) {
        val settings = settingsRepo.getLatestSettings()
        val activePoints = settings.allPoints.filter { it.enabled }

        if (activePoints.isEmpty()) {
            container.addView(emptyText("Нет активных точек.\nВключите нужные точки в приложении."))
            return
        }

        for (pt in activePoints) {
            val card = card()
            card.addView(
                infoRow(
                    "Точка ${pt.id}",
                    if (pt.isConfigured) "X=${pt.x.toInt()}  Y=${pt.y.toInt()}" else "не задана",
                    if (pt.isConfigured) 0xFF8CFF00.toInt() else 0xFFFF5252.toInt()
                )
            )
            card.addView(
                twoButtons(
                    glassButton(context, "Применить", on = true, textSp = 12f) {
                        callbacks.onStartPointCalibration(pt.id)
                    },
                    glassButton(context, "Тест", hueOffset = 40f, textSp = 12f) {
                        cycleController.testClick(pt.id)
                    }.apply { isEnabled = pt.isConfigured }
                )
            )
            container.addView(card)
        }
    }

    // ===== «СВАЙПЫ» =====
    private fun populateSwipes(container: LinearLayout) {
        val settings = settingsRepo.getLatestSettings()
        val activeSwipes = settings.swipes.filter { it.enabled }

        if (activeSwipes.isEmpty()) {
            container.addView(emptyText("Нет активных свайпов.\nВключите нужные свайпы в приложении."))
            return
        }

        for (sw in activeSwipes) {
            val card = card()
            card.addView(
                infoRow(
                    "Свайп ${sw.id}",
                    if (sw.isConfigured) "(${sw.startX.toInt()},${sw.startY.toInt()})→(${sw.endX.toInt()},${sw.endY.toInt()})" else "не задан",
                    if (sw.isConfigured) 0xFF8CFF00.toInt() else 0xFFFF5252.toInt(),
                    10f
                )
            )
            card.addView(
                twoButtons(
                    glassButton(context, "Применить", on = true, textSp = 12f) {
                        callbacks.onStartSwipeCalibration(sw.id, true)
                    },
                    glassButton(context, "Тест", hueOffset = 40f, textSp = 12f) {
                        swipeController.testSwipe(sw.id)
                    }.apply { isEnabled = sw.isConfigured }
                )
            )
            container.addView(card)
        }
    }

    // ===== «УМНЫЙ РЕЖИМ» =====
    private fun populateSmartMode(container: LinearLayout) {
        val settings = settingsRepo.getLatestSettings()
        val isApi30 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        container.addView(
            toggle("Умный режим", settings.isSmartMode && isApi30, enabled = isApi30) {
                settingsRepo.updateSmartMode(it)
            }
        )

        val currentMode = configManager.getMode()
        val isCustomActive = configManager.getActiveName().isNotEmpty() && currentMode != SmartConfigMode.DEFAULT_ONLY

        container.addView(
            toggle("Свой конфиг", isCustomActive) { isChecked ->
                if (isChecked) {
                    val available = configManager.listConfigs()
                    val target = if (configManager.getActiveName().isNotEmpty()) {
                        configManager.getActiveName()
                    } else {
                        available.lastOrNull() ?: ""
                    }
                    if (target.isNotEmpty()) configManager.setActiveName(target)
                    configManager.setMode(SmartConfigMode.CUSTOM_ONLY)
                } else {
                    configManager.setActiveName("")
                    configManager.setMode(SmartConfigMode.DEFAULT_ONLY)
                }
                populateContent(WindowType.SMART)
            }
        )

        container.addView(neonLabel(context, "ВЫБОР КОНФИГА"))

        val isDefaultActive = configManager.getActiveName().isEmpty() || currentMode == SmartConfigMode.DEFAULT_ONLY
        container.addView(
            fullButton(
                glassButton(context, if (isDefaultActive) "✔ по умолчанию (PUBG)" else "по умолчанию (PUBG)", 44f, 18f, 0f, isDefaultActive, 12f) {
                    configManager.setActiveName("")
                    configManager.setMode(SmartConfigMode.DEFAULT_ONLY)
                    populateContent(WindowType.SMART)
                }, 44, 6
            )
        )

        val userConfigs = configManager.listConfigs()
        if (userConfigs.isEmpty()) {
            container.addView(emptyText("Конфиги не загружены.\nЗагрузите zip в приложении (Меню)."))
        } else {
            for ((i, cfg) in userConfigs.withIndex()) {
                val isActive = configManager.getActiveName() == cfg && currentMode != SmartConfigMode.DEFAULT_ONLY
                container.addView(
                    fullButton(
                        glassButton(context, if (isActive) "✔ $cfg" else cfg, 44f, 18f, 15f * (i + 1), isActive, 12f) {
                            configManager.setActiveName(cfg)
                            configManager.setMode(SmartConfigMode.CUSTOM_ONLY)
                            populateContent(WindowType.SMART)
                        }, 44, 6
                    )
                )
            }
        }
    }

    // ===== «ЕЩЁ» =====
    private fun populateMore(container: LinearLayout) {
        val settings = settingsRepo.getLatestSettings()

        // Таймер цикла
        container.addView(neonLabel(context, "ВРЕМЯ ЦИКЛА"))
        container.addView(
            slider(1, 15, settings.cycleDelayMinutes, { "$it мин" }) { settingsRepo.updateCycleDelay(it) }
        )

        // Неон: яркость и цвет
        container.addView(neonLabel(context, "ЯРКОСТЬ НЕОНА"))
        container.addView(
            slider(0, 100, settings.neonBrightness, { "$it%" }) { settingsRepo.updateNeonBrightness(it) }
        )
        container.addView(
            toggle("Автосмена цвета", NeonTheme.isAuto) { NeonTheme.setAuto(it) }
        )
        container.addView(
            fullButton(
                glassButton(context, "Следующий цвет неона", 44f, 18f, 30f, false, 12f) {
                    NeonTheme.setHue(NeonTheme.getHue() + 30f)
                    populateContent(WindowType.MORE)
                }, 44, 8
            )
        )

        // Макрос
        container.addView(neonLabel(context, "МАКРОС"))
        val macroCard = card()
        val hasMacro = settings.recordedMacro != null && settings.recordedMacro.isNotEmpty
        val isRunning = macroController.isRunning.value
        macroCard.addView(TextView(context).apply {
            text = if (isRunning) {
                "▶ Выполняется"
            } else if (hasMacro) {
                "Записан (${settings.recordedMacro?.formattedDuration})"
            } else {
                "Не записан"
            }
            setTextColor(if (hasMacro) 0xFF8CFF00.toInt() else 0xFFB0BEC5.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(2), 0, dp(2))
        })
        macroCard.addView(
            twoButtons(
                glassButton(context, if (isRunning) "■ Стоп" else "▶ Старт", hueOffset = if (isRunning) -110f else 0f, on = isRunning, textSp = 12f) {
                    if (macroController.isRunning.value) macroController.stop()
                    else macroController.start(settings.macroRepeatCount, settings.macroIntervalSec)
                    populateContent(WindowType.MORE)
                }.apply { isEnabled = hasMacro || isRunning },
                glassButton(context, "Очистить", hueOffset = -60f, textSp = 12f) {
                    settingsRepo.clearMacro()
                    populateContent(WindowType.MORE)
                }.apply { isEnabled = hasMacro }
            )
        )
        container.addView(macroCard)

        // Остальное
        container.addView(
            fullButton(glassButton(context, "Метки точек на экране", 44f, 18f, 20f, false, 12f) {
                callbacks.onToggleVisualOverlay()
            })
        )
        container.addView(
            fullButton(glassButton(context, "Открыть приложение", 44f, 18f, 40f, false, 12f) {
                callbacks.onOpenMainActivity()
                dismiss()
            })
        )
        container.addView(
            fullButton(glassButton(context, "Закрыть плавающее окно", 44f, 18f, -110f, false, 12f) {
                callbacks.onCloseService()
            }.apply { setTextColor(0xFFFF8A80.toInt()) }, 44, 0)
        )
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
        sliders.clear()
        callbacks.onDismiss()
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
