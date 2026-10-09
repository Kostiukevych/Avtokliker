package com.example.autoclicker.service

import kotlinx.coroutines.launch

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
        fun onStartJoystickCalibration(kind: Int)
        fun onOpenMainActivity()
        fun onToggleVisualOverlay()
        fun onCloseService()
        fun onDismiss()
        fun onStartMacroRecording()
        fun onOpenBadgeSettings()
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
    private var schedDayOffset = 1
    private var schedHour = 9
    private var schedMinute = 0

    private fun dp(value: Int): Int = (value * density).toInt()

    fun isShowing(): Boolean = isShown

    fun show(type: WindowType, anchorX: Int = 0, anchorY: Int = 0, anchorWidth: Int = 0, anchorHeight: Int = 0) {
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

    private fun twoButtons(left: View, mid: View, right: View): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4); bottomMargin = dp(4) }
            left.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            mid.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
            right.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(left)
            addView(mid)
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

    private fun showConfirmDialog(title: String, message: String, onConfirm: () -> Unit) {
        val dialog = android.app.AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Сбросить") { _, _ -> onConfirm() }
            .setNegativeButton("Отмена", null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }

    // ===== «ТОЧКИ» =====
    private fun populatePoints(container: LinearLayout) {
        val settings = settingsRepo.getLatestSettings()

        if (settings.isSmartMode) {
            container.addView(TextView(context).apply {
                text = "Умный режим включён: точки не нажимаются"
                setTextColor(Color.parseColor("#FF5252"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(4), dp(2), dp(4), dp(8))
            })
        }

        val activePoints = settings.allPoints.filter { it.enabled }

        if (activePoints.isEmpty()) {
            container.addView(emptyText("Нет активных точек.\nВключите нужные точки в приложении."))
        } else {
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
                card.addView(
                    fullButton(
                        glassButton(context, "Сбросить точку", hueOffset = -90f, textSp = 11f) {
                            showConfirmDialog("Вы уверены?", "Сбросить точку ${pt.id}?") {
                                com.example.autoclicker.data.ResetManager.resetPoint(context, pt.id)
                            }
                        }, heightDp = 34, bottomDp = 2
                    )
                )
                container.addView(card)
            }
        }

        container.addView(
            fullButton(
                glassButton(context, "Сбросить все точки", hueOffset = -90f, textSp = 12f) {
                    showConfirmDialog("Вы уверены?", "Сбросить все 10 точек?") {
                        com.example.autoclicker.data.ResetManager.resetAllPoints(context)
                    }
                }, heightDp = 42, bottomDp = 4
            )
        )
        container.addView(TextView(context).apply {
            text = "Выключенные точки не показываются на экране"
            setTextColor(0xFF90A4AE.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(2), dp(4), dp(8))
        })
    }

    // ===== «СВАЙПЫ» =====
    private fun populateSwipes(container: LinearLayout) {
        val settings = settingsRepo.getLatestSettings()
        val activeSwipes = settings.swipes.filter { it.enabled }

        if (activeSwipes.isEmpty()) {
            container.addView(emptyText("Нет активных свайпов.\nВключите нужные свайпы в приложении."))
        } else {
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
                card.addView(
                    fullButton(
                        glassButton(context, "Сбросить свайп", hueOffset = -90f, textSp = 11f) {
                            showConfirmDialog("Вы уверены?", "Сбросить свайп ${sw.id}?") {
                                com.example.autoclicker.data.ResetManager.resetSwipe(context, sw.id)
                            }
                        }, heightDp = 34, bottomDp = 2
                    )
                )
                container.addView(card)
            }
        }

        container.addView(
            fullButton(
                glassButton(context, "Сбросить все свайпы", hueOffset = -90f, textSp = 12f) {
                    showConfirmDialog("Вы уверены?", "Сбросить все свайпы?") {
                        com.example.autoclicker.data.ResetManager.resetAllSwipes(context)
                    }
                }, heightDp = 42, bottomDp = 8
            )
        )
    }

    // ===== «УМНЫЙ РЕЖИМ» =====
    private fun populateSmartMode(container: LinearLayout) {
        val settings = settingsRepo.getLatestSettings()
        val isApi30 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        container.addView(
            toggle("Умный режим", settings.isSmartMode && isApi30, enabled = isApi30) {
                settingsRepo.updateRunSmart(it)
            }
        )

        val descView = TextView(context).apply {
            text = "Активный конфиг: " + configManager.describeActive()
            setTextColor(Color.parseColor("#80D8FF"))
            textSize = 12f
            setPadding(dp(8), dp(2), dp(8), dp(6))
        }
        container.addView(descView)

        container.addView(
            fullButton(
                glassButton(context, "Сбросить умный режим", hueOffset = -90f, textSp = 12f) {
                    showConfirmDialog("Вы уверены?", "Сбросить умный режим?") {
                        com.example.autoclicker.data.ResetManager.resetSmartMode(context)
                        populateContent(WindowType.SMART)
                    }
                }, heightDp = 40, bottomDp = 8
            )
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

        val configsCountView = TextView(context).apply {
            text = "Конфигов: ${configManager.listConfigs().size} из 5"
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 12f
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        container.addView(configsCountView)

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

        // ===== НАСТРОЙКИ ПЛАВАЮЩЕЙ КНОПКИ =====
        container.addView(neonLabel(context, "ПЛАВАЮЩАЯ КНОПКА"))
        container.addView(
            glassButton(context, "Настройки кнопки (размер, прозрачность…)", hueOffset = 10f, textSp = 12f) {
                callbacks.onOpenBadgeSettings()
            }
        )
        container.addView(TextView(context).apply {
            text = "Открывает настройки самой плавающей кнопки (чёрная дыра)."
            setTextColor(0xFF90A4AE.toInt())
            textSize = 11f
            setPadding(dp(8), dp(2), dp(8), dp(8))
        })

        // ===== ДЖОЙСТИКИ =====
        val joyRepo = com.example.autoclicker.data.JoystickRepository.getInstance(context)
        val joy = joyRepo.getLatest()
        container.addView(neonLabel(context, "ДЖОЙСТИКИ"))
        container.addView(
            toggle("Джойстики вместе с умным режимом", joy.masterEnabled) {
                joyRepo.setMaster(it)
            }
        )
        container.addView(
            toggle("Автомимикрия (сценарий на всю катку)", joy.autoMimicEnabled) {
                joyRepo.setAutoMimic(it)
            }
        )
        container.addView(TextView(context).apply {
            text = "Автомимикрия: движения по сценарию до «Продолжить», затем пауза и повтор после «Начать». Нужны поставленные J1/J2."
            setTextColor(0xFF90A4AE.toInt())
            textSize = 11f
            setPadding(dp(8), dp(2), dp(8), dp(6))
        })
        val angles = listOf(0, 45, 90, 135, 180, 225, 270, 315)
        val angleLabels = listOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")
        fun stickCard(id: Int) {
            val st = if (id == 1) joy.stick1 else joy.stick2
            val c = card()
            c.addView(toggle("J$id включён", st.enabled) { en ->
                joyRepo.updateStick(id) { it.copy(enabled = en) }
                populateContent(WindowType.SMART)
            })
            c.addView(
                infoRow(
                    "J$id центр",
                    if (st.isConfigured) "X=${st.x.toInt()} Y=${st.y.toInt()}" else "не задан",
                    if (st.isConfigured) 0xFF80D8FF.toInt() else 0xFFB0BEC5.toInt()
                )
            )
            val testEnabled = st.isConfigured && !com.example.autoclicker.engine.SmartEngine.getInstance(
                context, com.example.autoclicker.engine.GestureExecutor(), settingsRepo
            ).isRunning
            c.addView(
                twoButtons(
                    glassButton(context, "Поставить", hueOffset = 20f, textSp = 11f) {
                        callbacks.onStartJoystickCalibration(id)
                    },
                    glassButton(context, "Тест", hueOffset = 40f, textSp = 11f) {
                        if (!st.isConfigured) return@glassButton
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                            com.example.autoclicker.engine.JoystickController.getInstance(
                                com.example.autoclicker.engine.GestureExecutor(), settingsRepo
                            ).test(id)
                        }
                    }.apply { isEnabled = testEnabled }
                )
            )
            c.addView(slider(40, 400, st.radiusPx, { "радиус $it px" }) { v ->
                joyRepo.updateStick(id) { it.copy(radiusPx = v) }
            })
            c.addView(slider(30, 100, st.strength, { "сила $it%" }) { v ->
                joyRepo.updateStick(id) { it.copy(strength = v) }
            })
            val modeLabel = if (st.mode == "circle") "Режим: круг" else "Режим: удержание"
            c.addView(
                fullButton(
                    glassButton(context, modeLabel, hueOffset = 10f, textSp = 11f) {
                        val next = if (st.mode == "hold") "circle" else "hold"
                        joyRepo.updateStick(id) { it.copy(mode = next) }
                        populateContent(WindowType.SMART)
                    }, heightDp = 36, bottomDp = 4
                )
            )
            if (st.mode == "hold") {
                val idx = angles.indexOf(st.angleDeg).let { if (it < 0) 0 else it }
                c.addView(
                    fullButton(
                        glassButton(context, "Направление: ${angleLabels[idx]}", hueOffset = 25f, textSp = 11f) {
                            val next = angles[(idx + 1) % angles.size]
                            joyRepo.updateStick(id) { it.copy(angleDeg = next) }
                            populateContent(WindowType.SMART)
                        }, heightDp = 36, bottomDp = 4
                    )
                )
            } else {
                c.addView(slider(2, 60, st.circlePeriodSec, { "$it с/оборот" }) { v ->
                    joyRepo.updateStick(id) { it.copy(circlePeriodSec = v) }
                })
            }
            c.addView(
                fullButton(
                    glassButton(context, "Сбросить J$id", hueOffset = -90f, textSp = 11f) {
                        showConfirmDialog("Вы уверены?", "Сбросить J$id?") {
                            joyRepo.resetStick(id)
                            populateContent(WindowType.SMART)
                        }
                    }, heightDp = 36, bottomDp = 6
                )
            )
            container.addView(c)
        }
        stickCard(1)
        stickCard(2)
        // Button B
        val btn = joy.button
        val bc = card()
        bc.addView(toggle("Кнопка B включена", btn.enabled) { en ->
            joyRepo.updateButton { it.copy(enabled = en) }
            populateContent(WindowType.SMART)
        })
        bc.addView(
            infoRow(
                "B позиция",
                if (btn.isConfigured) "X=${btn.x.toInt()} Y=${btn.y.toInt()}" else "не задана",
                if (btn.isConfigured) 0xFFFF4081.toInt() else 0xFFB0BEC5.toInt()
            )
        )
        bc.addView(
            fullButton(
                glassButton(context, "Поставить B", hueOffset = 30f, textSp = 11f) {
                    callbacks.onStartJoystickCalibration(3)
                }, heightDp = 36, bottomDp = 4
            )
        )
        val bMode = if (btn.mode == "hold") "Режим: Удержание" else "Режим: Тап"
        bc.addView(
            fullButton(
                glassButton(context, bMode, hueOffset = 15f, textSp = 11f) {
                    val next = if (btn.mode == "tap") "hold" else "tap"
                    joyRepo.updateButton { it.copy(mode = next) }
                    populateContent(WindowType.SMART)
                }, heightDp = 36, bottomDp = 4
            )
        )
        if (btn.mode == "tap") {
            bc.addView(slider(100, 5000, btn.intervalMs, { "каждые $it мс" }) { v ->
                joyRepo.updateButton { it.copy(intervalMs = v) }
            })
        }
        bc.addView(
            fullButton(
                glassButton(context, "Сбросить кнопку", hueOffset = -90f, textSp = 11f) {
                    showConfirmDialog("Вы уверены?", "Сбросить кнопку B?") {
                        joyRepo.resetButton()
                        populateContent(WindowType.SMART)
                    }
                }, heightDp = 36, bottomDp = 6
            )
        )
        container.addView(bc)
        container.addView(TextView(context).apply {
            text = "Джойстики работают только пока включён умный режим"
            setTextColor(0xFF90A4AE.toInt())
            textSize = 11f
            setPadding(dp(8), dp(4), dp(8), dp(8))
        })

    }

    // ===== «ЕЩЁ» =====
    private fun populateMore(container: LinearLayout) {
        val settings = settingsRepo.getLatestSettings()

        // Таймер цикла
        container.addView(neonLabel(context, "ВРЕМЯ ЦИКЛА"))
        container.addView(
            slider(1, 15, settings.cycleDelayMinutes, { "$it мин" }) { settingsRepo.updateCycleDelay(it) }
        )
        container.addView(
            toggle("Первый запуск: все точки", settings.firstCycleAllPoints) {
                settingsRepo.updateFirstCycleAllPoints(it)
            }
        )
        container.addView(
            fullButton(
                glassButton(context, "Сбросить таймер цикла", hueOffset = -90f, textSp = 11f) {
                    showConfirmDialog("Вы уверены?", "Сбросить таймер цикла?") {
                        com.example.autoclicker.data.ResetManager.resetCycleDelay(context)
                        populateContent(WindowType.MORE)
                    }
                }, heightDp = 36, bottomDp = 8
            )
        )

        // Неон: яркость и цвет
        container.addView(neonLabel(context, "ЯРКОСТЬ НЕОНА"))
        container.addView(
            slider(0, 100, settings.neonBrightness, { "$it%" }) { settingsRepo.updateNeonBrightness(it) }
        )
        container.addView(
            fullButton(
                glassButton(context, "Сбросить яркость неона", hueOffset = -90f, textSp = 11f) {
                    showConfirmDialog("Вы уверены?", "Сбросить яркость неона?") {
                        com.example.autoclicker.data.ResetManager.resetNeonBrightness(context)
                        populateContent(WindowType.MORE)
                    }
                }, heightDp = 36, bottomDp = 8
            )
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
        val recordLabel = if (hasMacro) "🔴 Перезаписать" else "🔴 Записать"
        macroCard.addView(
            fullButton(
                glassButton(context, recordLabel, hueOffset = -30f, textSp = 13f) {
                    if (!macroController.isRunning.value) {
                        callbacks.onStartMacroRecording()
                    }
                }.apply { isEnabled = !isRunning },
                heightDp = 44, bottomDp = 6
            )
        )
        macroCard.addView(
            twoButtons(
                glassButton(context, if (isRunning) "■ Стоп" else "▶ Старт", hueOffset = if (isRunning) -110f else 0f, on = isRunning, textSp = 12f) {
                    if (macroController.isRunning.value) macroController.stop()
                    else macroController.start(settings.macroRepeatCount, settings.macroIntervalSec)
                    populateContent(WindowType.MORE)
                }.apply { isEnabled = hasMacro || isRunning },
                glassButton(context, "Удалить макрос", hueOffset = -60f, textSp = 12f) {
                    showConfirmDialog("Вы уверены?", "Удалить записанный макрос?") {
                        com.example.autoclicker.data.ResetManager.deleteAllMacros(context)
                        populateContent(WindowType.MORE)
                    }
                }.apply { isEnabled = hasMacro }
            )
        )
        container.addView(macroCard)

        // Отложенный запуск
        container.addView(neonLabel(context, "ОТЛОЖЕННЫЙ ЗАПУСК"))
        val sched = settings
        val schedInfo = if (sched.scheduleEnabled && sched.scheduleAtEpochMs > 0L) {
            val fmt = java.text.SimpleDateFormat("EEE dd.MM HH:mm", java.util.Locale.getDefault())
            val left = sched.scheduleAtEpochMs - System.currentTimeMillis()
            val leftStr = if (left > 0) {
                val sec = left / 1000
                val d = sec / 86400; val h = (sec % 86400) / 3600; val m = (sec % 3600) / 60; val s = sec % 60
                " (через ${if (d > 0) "${d}д " else ""}${h.toString().padStart(2,'0')}:${m.toString().padStart(2,'0')}:${s.toString().padStart(2,'0')})"
            } else " (сейчас)"
            "Вкл: ${fmt.format(java.util.Date(sched.scheduleAtEpochMs))}$leftStr"
        } else {
            "Выкл — старт сразу"
        }
        container.addView(TextView(context).apply {
            text = schedInfo
            setTextColor(0xFF80D8FF.toInt())
            textSize = 12f
            setPadding(dp(8), dp(2), dp(8), dp(6))
        })
        // Степперы день/час/минута
        fun dayLabel(): String {
            val cal = java.util.Calendar.getInstance()
            cal.add(java.util.Calendar.DAY_OF_YEAR, schedDayOffset)
            val date = java.text.SimpleDateFormat("dd.MM", java.util.Locale.getDefault()).format(cal.time)
            return when (schedDayOffset) {
                0 -> "Сегодня ($date)"
                1 -> "Завтра ($date)"
                2 -> "Послезавтра ($date)"
                else -> "Через $schedDayOffset дн. ($date)"
            }
        }
        val dayTv = TextView(context).apply {
            text = dayLabel()
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            gravity = android.view.Gravity.CENTER
        }
        container.addView(twoButtons(
            glassButton(context, "−", 36f, 14f, 0f, false, 14f) {
                schedDayOffset = (schedDayOffset - 1).coerceAtLeast(0)
                dayTv.text = dayLabel()
            },
            dayTv,
            glassButton(context, "+", 36f, 14f, 0f, false, 14f) {
                schedDayOffset = (schedDayOffset + 1).coerceAtMost(60)
                dayTv.text = dayLabel()
            }
        ))
        val hourTv = TextView(context).apply {
            text = "%02d".format(schedHour)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            gravity = android.view.Gravity.CENTER
        }
        container.addView(twoButtons(
            glassButton(context, "−ч", 36f, 14f, 0f, false, 12f) {
                schedHour = (schedHour + 23) % 24
                hourTv.text = "%02d".format(schedHour)
            },
            hourTv,
            glassButton(context, "+ч", 36f, 14f, 0f, false, 12f) {
                schedHour = (schedHour + 1) % 24
                hourTv.text = "%02d".format(schedHour)
            }
        ))
        val minTv = TextView(context).apply {
            text = "%02d".format(schedMinute)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            gravity = android.view.Gravity.CENTER
        }
        container.addView(twoButtons(
            glassButton(context, "−1м", 36f, 12f, 0f, false, 11f) {
                schedMinute = (schedMinute + 59) % 60
                minTv.text = "%02d".format(schedMinute)
            },
            minTv,
            glassButton(context, "+1м", 36f, 12f, 0f, false, 11f) {
                schedMinute = (schedMinute + 1) % 60
                minTv.text = "%02d".format(schedMinute)
            }
        ))
        val summaryTv = TextView(context).apply {
            val cal = java.util.Calendar.getInstance()
            cal.add(java.util.Calendar.DAY_OF_YEAR, schedDayOffset)
            cal.set(java.util.Calendar.HOUR_OF_DAY, schedHour)
            cal.set(java.util.Calendar.MINUTE, schedMinute)
            text = "Старт: " + java.text.SimpleDateFormat("EEE dd.MM HH:mm", java.util.Locale.getDefault()).format(cal.time)
            setTextColor(0xFF80D8FF.toInt())
            textSize = 12f
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        container.addView(summaryTv)
        container.addView(
            fullButton(glassButton(context, "Включить отложенный старт", 42f, 16f, 40f, false, 12f) {
                val cal = java.util.Calendar.getInstance()
                cal.add(java.util.Calendar.DAY_OF_YEAR, schedDayOffset)
                cal.set(java.util.Calendar.HOUR_OF_DAY, schedHour)
                cal.set(java.util.Calendar.MINUTE, schedMinute)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                if (cal.timeInMillis <= System.currentTimeMillis()) {
                    android.widget.Toast.makeText(context, "Это время уже прошло", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    settingsRepo.updateSchedule(true, cal.timeInMillis, "all")
                    val fmt = java.text.SimpleDateFormat("EEE dd.MM HH:mm", java.util.Locale.getDefault())
                    android.widget.Toast.makeText(context, "Старт: ${fmt.format(cal.time)}", android.widget.Toast.LENGTH_SHORT).show()
                    populateContent(WindowType.MORE)
                }
            }, 42, 6)
        )
        // Быстрые пресеты
        container.addView(
            fullButton(glassButton(context, "Через 1 час", 40f, 16f, 20f, false, 11f) {
                val at = System.currentTimeMillis() + 60L * 60L * 1000L
                settingsRepo.updateSchedule(true, at, "all")
                android.widget.Toast.makeText(context, "Старт через 1 час", android.widget.Toast.LENGTH_SHORT).show()
                populateContent(WindowType.MORE)
            }, 40, 4)
        )
        container.addView(
            fullButton(glassButton(context, "Завтра в 09:00", 40f, 16f, 20f, false, 11f) {
                val cal = java.util.Calendar.getInstance()
                cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                cal.set(java.util.Calendar.HOUR_OF_DAY, 9)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                settingsRepo.updateSchedule(true, cal.timeInMillis, "all")
                android.widget.Toast.makeText(context, "Старт завтра 09:00", android.widget.Toast.LENGTH_SHORT).show()
                populateContent(WindowType.MORE)
            }, 40, 4)
        )
        container.addView(
            fullButton(glassButton(context, "Через 7 дней 09:00", 40f, 16f, 20f, false, 11f) {
                val cal = java.util.Calendar.getInstance()
                cal.add(java.util.Calendar.DAY_OF_YEAR, 7)
                cal.set(java.util.Calendar.HOUR_OF_DAY, 9)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                settingsRepo.updateSchedule(true, cal.timeInMillis, "all")
                android.widget.Toast.makeText(context, "Старт через неделю 09:00", android.widget.Toast.LENGTH_SHORT).show()
                populateContent(WindowType.MORE)
            }, 40, 4)
        )
        container.addView(
            fullButton(glassButton(context, "Сбросить расписание", 40f, 16f, -90f, false, 11f) {
                settingsRepo.clearSchedule()
                android.widget.Toast.makeText(context, "Расписание выключено", android.widget.Toast.LENGTH_SHORT).show()
                populateContent(WindowType.MORE)
            }, 40, 8)
        )

        // Сброс всего
        container.addView(neonLabel(context, "СБРОС"))
        container.addView(
            fullButton(glassButton(context, "Удалить загруженные конфиги", 40f, 16f, -90f, false, 11f) {
                showConfirmDialog("Вы уверены?", "Удалить все пользовательские конфиги?") {
                    com.example.autoclicker.data.ResetManager.deleteAllCustomConfigs(context)
                    populateContent(WindowType.MORE)
                }
            }, 40, 6)
        )
        container.addView(
            fullButton(glassButton(context, "Сбросить точки, свайпы и запись", 42f, 16f, -90f, false, 11f) {
                showConfirmDialog("Вы уверены?", "Сбросить все точки, свайпы и записанный макрос?") {
                    com.example.autoclicker.data.ResetManager.resetActions(context)
                    populateContent(WindowType.MORE)
                }
            }.apply { setTextColor(0xFFFF8A80.toInt()) }, 42, 6)
        )
        container.addView(
            fullButton(glassButton(context, "Сбросить ВСЁ", 44f, 18f, -110f, false, 12f) {
                showConfirmDialog("Вы уверены?", "Сбросить абсолютно все настройки, точки и режимы?") {
                    com.example.autoclicker.data.ResetManager.resetAll(context)
                    populateContent(WindowType.MORE)
                }
            }.apply { setTextColor(0xFFFF8A80.toInt()) }, 44, 10)
        )

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
