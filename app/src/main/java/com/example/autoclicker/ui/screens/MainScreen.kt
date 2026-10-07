package com.example.autoclicker.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.autoclicker.R
import com.example.autoclicker.data.ClickerSettings
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.data.SmartConfigManager
import com.example.autoclicker.data.SmartConfigMode
import com.example.autoclicker.data.SwipeAction
import com.example.autoclicker.engine.CycleStatus
import com.example.autoclicker.ui.theme.AccentAmber
import com.example.autoclicker.ui.theme.AccentGreen
import com.example.autoclicker.ui.theme.AccentRed
import com.example.autoclicker.ui.theme.BurgerIcon
import com.example.autoclicker.ui.components.BlackHoleButton
import com.example.autoclicker.ui.theme.GlassButton
import com.example.autoclicker.ui.theme.GlassDialog
import com.example.autoclicker.ui.theme.GlassLabel
import com.example.autoclicker.ui.theme.GlassMenuPanel
import com.example.autoclicker.ui.theme.GlassPanel
import com.example.autoclicker.ui.theme.GlassPointButton
import com.example.autoclicker.ui.theme.GlassSlider
import com.example.autoclicker.ui.theme.GlassSquareButton
import com.example.autoclicker.ui.theme.GlassSwitch
import com.example.autoclicker.ui.theme.LiquidReservoir
import com.example.autoclicker.ui.theme.LocalNeonHue
import com.example.autoclicker.ui.theme.NeonColorPicker
import com.example.autoclicker.ui.theme.NeonTheme
import com.example.autoclicker.ui.theme.ProvideNeon
import com.example.autoclicker.ui.theme.TextMuted
import com.example.autoclicker.ui.theme.TextPrimary
import com.example.autoclicker.ui.theme.TextSecondary
import com.example.autoclicker.ui.theme.liquidGlassBackground
import com.example.autoclicker.ui.theme.nc

@Composable
fun MainScreen(
    settings: ClickerSettings,
    logs: List<String>,
    cycleStatus: CycleStatus,
    currentAction: String,
    lastAction: String,
    nextAction: String,
    remainingSeconds: Int,
    countdownText: String,
    cycleNumber: Int,
    isAccessibilityConnected: Boolean,
    isOverlayGranted: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onLaunchOverlayService: () -> Unit,
    onStartCycle: () -> Unit,
    onStopCycle: () -> Unit,
    onTestClick: (Int) -> Unit,
    onUpdatePointConfig: (Int, Boolean, Int, Int) -> Unit,
    onUpdateCycleDelay: (Int) -> Unit,
    onUpdateSmartMode: (Boolean) -> Unit = {},
    onUpdateRunPoints: (Boolean) -> Unit = {},
    onUpdateRunSwipes: (Boolean) -> Unit = {},
    onUpdateRunSmart: (Boolean) -> Unit = {},
    onUpdateFirstCycleAllPoints: (Boolean) -> Unit = {},
    onResetPoint: (Int) -> Unit = {},
    onResetAllPoints: () -> Unit = {},
    onResetSwipe: (Int) -> Unit = {},
    onResetAllSwipes: () -> Unit = {},
    onDeleteAllMacros: () -> Unit = {},
    onResetSmartMode: () -> Unit = {},
    onResetCycleDelay: () -> Unit = {},
    onResetNeonBrightness: () -> Unit = {},
    onResetActions: () -> Unit = {},
    onResetAll: () -> Unit = {},
    onDeleteAllCustomConfigs: () -> Unit = {},
    onUpdateDebugScreenshots: (Boolean) -> Unit = {},
    onUpdateSwipesMasterEnabled: (Boolean) -> Unit = {},
    onUpdateSwipeConfig: (Int, Boolean, Long, Int) -> Unit = { _, _, _, _ -> },
    onTestSwipe: (Int) -> Unit = {},
    isMacroRunning: Boolean = false,
    onStartMacro: () -> Unit = {},
    onStopMacro: () -> Unit = {},
    onStartMacroRecording: () -> Unit = {},
    onClearMacro: () -> Unit = {},
    onUpdateMacroConfig: (Int, Int) -> Unit = { _, _ -> },
    onClearLogs: () -> Unit,
    onUpdateNeonBrightness: (Int) -> Unit = {},
    onCheckConfig: (((com.example.autoclicker.engine.CheckReport) -> Unit) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    remember { NeonTheme.init(context); true }
    val settingsRepo = remember { SettingsRepository.getInstance(context) }
    val configManager = remember { SmartConfigManager.getInstance(context) }

    val neonBrightness = settings.neonBrightness
    val neonBriFraction = neonBrightness / 100f

    // Состояния диалогов настроек
    var editingPointId by remember { mutableStateOf<Int?>(null) }
    var editingSwipeId by remember { mutableStateOf<Int?>(null) }
    var isMacroDialogVisible by remember { mutableStateOf(false) }
    var confirmDialogState by remember { mutableStateOf<Triple<String, String, () -> Unit>?>(null) }

    // Выпадающее меню под бургером
    var isMenuOpen by remember { mutableStateOf(false) }
    var burgerRect by remember { mutableStateOf<Rect?>(null) }

    // Состояние конфигов
    var activeConfigName by remember { mutableStateOf(configManager.getActiveName()) }
    var configList by remember { mutableStateOf(configManager.listConfigs()) }
    var modeState by remember { mutableStateOf(configManager.getMode()) }
    var newlyImportedConfig by remember { mutableStateOf<String?>(null) }
    var importReport by remember { mutableStateOf<com.example.autoclicker.data.ImportReport?>(null) }
    var isCheckingConfig by remember { mutableStateOf(false) }
    var checkReportDialog by remember { mutableStateOf<com.example.autoclicker.engine.CheckReport?>(null) }
    var showPromptDialog by remember { mutableStateOf(false) }
    var showGuideDialog by remember { mutableStateOf(false) }

    val activeDescription = remember(activeConfigName, configList, modeState) {
        configManager.describeActive()
    }

    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val result = configManager.importZipWithReport(uri)
            result.onSuccess { report ->
                configList = configManager.listConfigs()
                modeState = configManager.getMode()
                importReport = report
            }.onFailure { err ->
                Toast.makeText(context, err.message ?: "Ошибка импорта", Toast.LENGTH_LONG).show()
            }
        }
    }

    ProvideNeon(brightness = neonBriFraction) {

        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .liquidGlassBackground()
        ) {
            val screenW = maxWidth
            val density = LocalDensity.current

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                // ===== АКВАРИУМ (верх экрана) =====
                LiquidReservoir(
                    cycleDelayMinutes = settings.cycleDelayMinutes,
                    neonBrightness = neonBrightness
                )

                Spacer(modifier = Modifier.height(14.dp))

                // ===== ГЛАВНАЯ СТЕКЛЯННАЯ ПАНЕЛЬ =====
                GlassPanel(modifier = Modifier.fillMaxWidth()) {

                    // Хедер: заголовок и бургер
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 10.dp, top = 2.dp, end = 2.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Автокликер",
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF1F4FB)
                        )
                        Box(Modifier.onGloballyPositioned { burgerRect = it.boundsInRoot() }) {
                            GlassSquareButton(onClick = { isMenuOpen = !isMenuOpen }, size = 52.dp) {
                                BurgerIcon(open = isMenuOpen)
                            }
                        }
                    }

                    // ----- РАЗРЕШЕНИЯ (исчезают после выдачи) -----
                    if (!isOverlayGranted || !isAccessibilityConnected) {
                        GlassLabel("РАЗРЕШЕНИЯ")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (!isOverlayGranted) {
                                GlassButton(
                                    onClick = onOpenOverlaySettings,
                                    modifier = Modifier.weight(1f),
                                    isOn = false,
                                    height = 76.dp,
                                    cornerRadius = 34.dp
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Поверх экрана", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF4F6FC))
                                        Text("SYSTEM_ALERT", fontSize = 12.sp, color = Color(0xFFC3CADF))
                                    }
                                }
                            }
                            if (!isAccessibilityConnected) {
                                GlassButton(
                                    onClick = onOpenAccessibilitySettings,
                                    modifier = Modifier.weight(1f),
                                    isOn = false,
                                    height = 76.dp,
                                    cornerRadius = 34.dp,
                                    hueOffset = 30f
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Accessibility", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF4F6FC))
                                        Text("для нажатий", fontSize = 12.sp, color = Color(0xFFC3CADF))
                                    }
                                }
                            }
                        }
                    }

                    // ----- ВРЕМЯ ЦИКЛА -----
                    GlassLabel("ВРЕМЯ ЦИКЛА")
                    GlassSlider(
                        value = settings.cycleDelayMinutes.toFloat(),
                        onValueChange = { onUpdateCycleDelay(it.toInt()) },
                        valueRange = 1f..15f,
                        steps = 13,
                        formattedValue = "${settings.cycleDelayMinutes} мин",
                        icon = { ClockIcon() }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    GlassSwitch(
                        text = "Первый запуск: все точки",
                        checked = settings.firstCycleAllPoints,
                        onCheckedChange = onUpdateFirstCycleAllPoints,
                        modifier = Modifier.testTag("first_cycle_all_points_switch")
                    )

                    // ----- РЕЖИМ -----
                    GlassLabel("РЕЖИМ")
                    val isApi30 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    GlassSwitch(
                        text = if (!isApi30) "Умный режим (нужен Android 11+)" else "Умный режим",
                        checked = settings.isSmartMode && isApi30,
                        onCheckedChange = { if (isApi30) onUpdateRunSmart(it) },
                        modifier = Modifier.testTag("smart_mode_switch")
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    GlassSwitch(
                        text = "Сохранять снимки (отладка)",
                        checked = settings.isDebugScreenshots,
                        onCheckedChange = onUpdateDebugScreenshots,
                        modifier = Modifier.testTag("debug_screenshots_switch")
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Активный конфиг: $activeDescription",
                        fontSize = 12.sp,
                        color = Color(0xFF80D8FF),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    GlassButton(
                        onClick = {
                            confirmDialogState = Triple(
                                "Вы уверены?",
                                "Сбросить флаги и активный конфиг умного режима к исходным?"
                            ) {
                                onResetSmartMode()
                                activeConfigName = configManager.getActiveName()
                                modeState = configManager.getMode()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        hueOffset = -90f
                    ) {
                        Text("Сбросить умный режим", color = AccentRed, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    // ----- ПОИНТЫ -----
                    GlassLabel("ПОИНТЫ (ТОЧКИ НАЖАТИЯ)")
                    if (settings.isSmartMode) {
                        Text(
                            text = "Умный режим включён: точки не нажимаются",
                            color = AccentRed,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (rowStart in listOf(1, 6)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                for (id in rowStart..(rowStart + 4)) {
                                    val p = settings.getPointById(id)
                                    GlassPointButton(
                                        id = id,
                                        enabled = p.enabled,
                                        onClick = { editingPointId = id },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    GlassButton(
                        onClick = {
                            confirmDialogState = Triple(
                                "Вы уверены?",
                                "Сбросить координаты и настройки всех 10 точек?"
                            ) { onResetAllPoints() }
                        },
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        hueOffset = -90f
                    ) {
                        Text("Сбросить все точки", color = AccentRed, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    // ----- СВАЙПЫ -----
                    GlassLabel("СВАЙПЫ")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        for (i in 1..3) {
                            val sw = settings.swipes.find { it.id == i }
                            val isSwOn = sw?.enabled == true
                            GlassButton(
                                onClick = { editingSwipeId = i },
                                modifier = Modifier.weight(1f),
                                isOn = isSwOn,
                                height = 76.dp,
                                cornerRadius = 34.dp,
                                hueOffset = i * 10f
                            ) {
                                Text("Свайп $i", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF4F6FC))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    GlassButton(
                        onClick = {
                            confirmDialogState = Triple(
                                "Вы уверены?",
                                "Сбросить параметры и координаты всех свайпов?"
                            ) { onResetAllSwipes() }
                        },
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        hueOffset = -90f
                    ) {
                        Text("Сбросить все свайпы", color = AccentRed, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    // ----- ЗАПИСЬ МАКРОСА -----
                    GlassLabel("ЗАПИСЬ")
                    GlassButton(
                        onClick = { isMacroDialogVisible = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btnRecord"),
                        isOn = settings.recordedMacro != null && settings.recordedMacro.isNotEmpty,
                        height = 76.dp,
                        cornerRadius = 34.dp,
                        hueOffset = -20f
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFFF5A6A))
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Запись макроса", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF4F6FC))
                            }
                            Text("нажатия, жесты, зажимы", fontSize = 12.sp, color = Color(0xFFC3CADF))
                        }
                    }

                    // ----- ЯРКОСТЬ НЕОНА -----
                    GlassLabel("ЯРКОСТЬ НЕОНА")
                    GlassSlider(
                        value = neonBrightness.toFloat(),
                        onValueChange = {
                            val b = it.toInt()
                            settingsRepo.updateNeonBrightness(b)
                            onUpdateNeonBrightness(b)
                        },
                        valueRange = 0f..100f,
                        steps = 0,
                        formattedValue = "$neonBrightness%",
                        icon = { SunIcon() }
                    )

                    // ----- УПРАВЛЕНИЕ ЦИКЛОМ -----
                    GlassLabel("УПРАВЛЕНИЕ ЦИКЛОМ")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val cycleInfo = if (cycleNumber > 0) " (Цикл #$cycleNumber)" else ""
                        Text(
                            text = "СТАТУС: ${cycleStatus.name}$cycleInfo",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = when (cycleStatus) {
                                CycleStatus.RUNNING -> AccentGreen
                                CycleStatus.WAITING_CYCLE -> AccentAmber
                                CycleStatus.STOPPED -> AccentRed
                            }
                        )
                        if (countdownText.isNotEmpty()) {
                            Text(
                                text = countdownText,
                                color = AccentAmber,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0xFF0F101A).copy(alpha = 0.85f), Color(0xFF06070B).copy(alpha = 0.92f))
                                )
                            )
                            .padding(12.dp)
                    ) {
                        Column {
                            Text("Текущее: $currentAction", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("Следующее: $nextAction", color = Color(0xFF80D8FF), fontSize = 12.sp)
                            Text("Последнее: $lastAction", color = TextMuted, fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // ----- ЧТО ЗАПУСКАТЬ -----
                    GlassLabel("ЧТО ЗАПУСКАТЬ")
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassSwitch(
                            text = "Точки",
                            checked = settings.runPoints,
                            onCheckedChange = onUpdateRunPoints,
                            modifier = Modifier.testTag("run_points_switch")
                        )
                        GlassSwitch(
                            text = "Свайпы",
                            checked = settings.runSwipes,
                            onCheckedChange = onUpdateRunSwipes,
                            modifier = Modifier.testTag("run_swipes_switch")
                        )
                        GlassSwitch(
                            text = "Умный режим",
                            checked = settings.isSmartMode,
                            onCheckedChange = onUpdateRunSmart,
                            modifier = Modifier.testTag("run_smart_switch")
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "ПОРЯДОК ЗАПУСКА",
                        color = TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    )
                    val orderItems = remember(settings.actionOrder) {
                        settings.actionOrder.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    }
                    val orderLabels = mapOf("smart" to "Умный", "points" to "Точки", "swipes" to "Свайпы")
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        orderItems.forEachIndexed { index, key ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "${index + 1}. ${orderLabels[key] ?: key}",
                                    color = Color(0xFF80D8FF),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                GlassButton(
                                    onClick = { settingsRepo.moveActionOrder(key, up = true) },
                                    modifier = Modifier.width(44.dp),
                                    height = 36.dp,
                                    cornerRadius = 12.dp
                                ) { Text("▲", color = Color.White, fontSize = 12.sp) }
                                GlassButton(
                                    onClick = { settingsRepo.moveActionOrder(key, up = false) },
                                    modifier = Modifier.width(44.dp),
                                    height = 36.dp,
                                    cornerRadius = 12.dp
                                ) { Text("▼", color = Color.White, fontSize = 12.sp) }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val isRunning = cycleStatus != CycleStatus.STOPPED
                        GlassButton(
                            onClick = onStartCycle,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("start_cycle_btn"),
                            isOn = isRunning,
                            height = 66.dp,
                            cornerRadius = 30.dp
                        ) {
                            Text(
                                text = if (isRunning) "▶ ЗАПУЩЕНО" else "START",
                                color = Color(0xFFF4F6FC),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        GlassButton(
                            onClick = onStopCycle,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("stop_cycle_btn"),
                            height = 66.dp,
                            cornerRadius = 30.dp,
                            hueOffset = -60f
                        ) {
                            Text("STOP", color = Color(0xFFFF8A80), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }

                    if (isOverlayGranted && isAccessibilityConnected) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            BlackHoleButton(
                                onClick = onLaunchOverlayService,
                                size = 132.dp,
                                showPlay = cycleStatus == CycleStatus.STOPPED,
                                modifier = Modifier.testTag("open_floating_window_btn")
                            )
                        }
                        Text(
                            text = "Нажмите — открыть плавающее окно",
                            color = Color(0xFF80D8FF),
                            fontSize = 12.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // ===== ЖУРНАЛ СОБЫТИЙ =====
                EventLogView(logs = logs, onClearLogs = onClearLogs)

                Spacer(modifier = Modifier.navigationBarsPadding().height(20.dp))
            }

            // ===== ДИАЛОГ: ПОИНТ =====
            if (editingPointId != null) {
                val ptId = editingPointId!!
                val pt = settings.getPointById(ptId)
                GlassDialog(
                    title = "Поинт $ptId",
                    onDismissRequest = { editingPointId = null },
                    onConfirm = { editingPointId = null }
                ) {
                    PointConfigContent(
                        point = pt,
                        onTestClick = { onTestClick(ptId) },
                        onToggleEnabled = { en -> onUpdatePointConfig(ptId, en, pt.clickCount, pt.intervalSec) },
                        onCountChange = { cnt -> onUpdatePointConfig(ptId, pt.enabled, cnt, pt.intervalSec) },
                        onIntervalChange = { intv -> onUpdatePointConfig(ptId, pt.enabled, pt.clickCount, intv) },
                        onResetPoint = {
                            editingPointId = null
                            onResetPoint(ptId)
                        }
                    )
                }
            }

            // ===== ДИАЛОГ: СВАЙП =====
            if (editingSwipeId != null) {
                val swId = editingSwipeId!!
                val sw = settings.swipes.find { it.id == swId } ?: SwipeAction(id = swId)
                GlassDialog(
                    title = "Свайп $swId",
                    onDismissRequest = { editingSwipeId = null },
                    onConfirm = { editingSwipeId = null }
                ) {
                    SingleSwipeContent(
                        swipe = sw,
                        onToggleEnabled = { en -> onUpdateSwipeConfig(swId, en, sw.durationMs, sw.intervalSec) },
                        onDurationChange = { dur -> onUpdateSwipeConfig(swId, sw.enabled, dur, sw.intervalSec) },
                        onIntervalChange = { intv -> onUpdateSwipeConfig(swId, sw.enabled, sw.durationMs, intv) },
                        onTestSwipe = { onTestSwipe(swId) },
                        onResetSwipe = {
                            editingSwipeId = null
                            onResetSwipe(swId)
                        }
                    )
                }
            }

            // ===== ДИАЛОГ: МАКРОС =====
            if (isMacroDialogVisible) {
                GlassDialog(
                    title = "Макрос / запись",
                    onDismissRequest = { isMacroDialogVisible = false },
                    onConfirm = { isMacroDialogVisible = false }
                ) {
                    MacroConfigContent(
                        recordedMacro = settings.recordedMacro,
                        repeatCount = settings.macroRepeatCount,
                        intervalSec = settings.macroIntervalSec,
                        isMacroRunning = isMacroRunning,
                        isAccessibilityConnected = isAccessibilityConnected,
                        onStartMacro = onStartMacro,
                        onStopMacro = onStopMacro,
                        onStartRecording = {
                            isMacroDialogVisible = false
                            onStartMacroRecording()
                        },
                        onClearMacro = onDeleteAllMacros,
                        onUpdateConfig = onUpdateMacroConfig
                    )
                }
            }

            // ===== ДИАЛОГ ПОДТВЕРЖДЕНИЯ СБРОСА =====
            if (confirmDialogState != null) {
                val (title, msg, onConfirm) = confirmDialogState!!
                GlassDialog(
                    title = title,
                    onDismissRequest = { confirmDialogState = null },
                    onConfirm = {
                        confirmDialogState = null
                        onConfirm()
                    },
                    confirmText = "Сбросить",
                    cancelText = "Отмена"
                ) {
                    Text(msg, color = Color.White, fontSize = 14.sp)
                }
            }

            // ===== ДИАЛОГ: ИТОГИ ИМПОРТА =====
            if (importReport != null) {
                val rep = importReport!!
                val totalWarn = rep.warnings.size + rep.problems.size
                GlassDialog(
                    title = "Итоги импорта",
                    onDismissRequest = {
                        newlyImportedConfig = rep.configName
                        importReport = null
                    },
                    onConfirm = {
                        newlyImportedConfig = rep.configName
                        importReport = null
                    },
                    confirmText = "Далее",
                    cancelText = "Закрыть"
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            "Загружено правил: ${rep.rulesCount}, шаблонов подготовлено: ${rep.templatesPrepared}, предупреждений: $totalWarn",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (rep.warnings.isNotEmpty() || rep.problems.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            rep.warnings.forEach { w ->
                                Text("• $w", color = Color(0xFFFFB74D), fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
                            }
                            rep.problems.forEach { p ->
                                Text("• $p", color = Color(0xFFEF5350), fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
                            }
                        }
                    }
                }
            }

            // ===== ДИАЛОГ: ПРОВЕРКА КОНФИГА =====
            if (checkReportDialog != null) {
                val rep = checkReportDialog!!
                GlassDialog(
                    title = "Проверка конфига",
                    onDismissRequest = { checkReportDialog = null },
                    onConfirm = { checkReportDialog = null },
                    confirmText = "Закрыть",
                    cancelText = "Отмена"
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(rep.message, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        if (rep.lines.isEmpty()) {
                            Text("Нет данных для отображения", color = TextMuted, fontSize = 12.sp)
                        } else {
                            rep.lines.forEach { line ->
                                val color = when {
                                    line.startsWith("Ошибка") -> Color(0xFFEF5350)
                                    line.contains("найдено (") -> Color(0xFF69F0AE)
                                    else -> TextSecondary
                                }
                                Text("• $line", color = color, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
                            }
                        }
                    }
                }
            }

            // ===== ДИАЛОГ: ПРОМПТ =====
            if (showPromptDialog) {
                val promptText = remember {
                    try {
                        context.resources.openRawResource(R.raw.config_prompt).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    } catch (_: Exception) {
                        "Ошибка чтения промпта"
                    }
                }
                GlassDialog(
                    title = "Промпт для нейросети",
                    onDismissRequest = { showPromptDialog = false },
                    onConfirm = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Config Prompt", promptText))
                        Toast.makeText(context, "Промпт скопирован", Toast.LENGTH_SHORT).show()
                        showPromptDialog = false
                    },
                    confirmText = "Копировать",
                    cancelText = "Закрыть"
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        SelectionContainer {
                            Text(promptText, fontSize = 12.sp, color = TextPrimary)
                        }
                    }
                }
            }

            // ===== ДИАЛОГ: ИНСТРУКЦИЯ =====
            if (showGuideDialog) {
                val guideText = remember {
                    try {
                        context.resources.openRawResource(R.raw.app_guide).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    } catch (_: Exception) {
                        "Ошибка чтения инструкции"
                    }
                }
                GlassDialog(
                    title = "Как пользоваться",
                    onDismissRequest = { showGuideDialog = false },
                    onConfirm = { showGuideDialog = false },
                    confirmText = "Закрыть",
                    cancelText = "Отмена"
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        guideText.lines().forEach { line ->
                            if (line.startsWith("## ")) {
                                Text(
                                    text = line.removePrefix("## "),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF00E5FF),
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                )
                            } else if (line.isNotBlank()) {
                                Text(line, fontSize = 12.sp, color = TextPrimary, modifier = Modifier.padding(vertical = 1.dp))
                            } else {
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                }
            }

            // ===== ДИАЛОГ: КОНФИГ ЗАГРУЖЕН =====
            if (newlyImportedConfig != null) {
                val importedName = newlyImportedConfig!!
                GlassDialog(
                    title = "Применить сейчас?",
                    onDismissRequest = { newlyImportedConfig = null },
                    onConfirm = {
                        configManager.setActiveName(importedName)
                        configManager.setMode(SmartConfigMode.CUSTOM_ONLY)
                        activeConfigName = importedName
                        modeState = configManager.getMode()
                        newlyImportedConfig = null
                        Toast.makeText(context, "Применён режим «Только мой»", Toast.LENGTH_SHORT).show()
                    },
                    confirmText = "Только мой",
                    cancelText = "Позже"
                ) {
                    Text(
                        "Конфиг '$importedName' успешно загружен. Какой режим включить?",
                        color = TextPrimary,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    GlassButton(
                        onClick = {
                            configManager.setActiveName(importedName)
                            configManager.setMode(SmartConfigMode.MERGED)
                            activeConfigName = importedName
                            modeState = configManager.getMode()
                            newlyImportedConfig = null
                            Toast.makeText(context, "Применён режим «Встроенный + мой»", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        height = 52.dp,
                        hueOffset = 20f
                    ) {
                        Text("Встроенный + мой", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            }

            // ===== ВЫПАДАЮЩЕЕ МЕНЮ (как в HTML: цвет неона + конфиги умного режима) =====
            if (isMenuOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { isMenuOpen = false }
                        )
                )
            }
            val menuWidth = minOf(360.dp, screenW - 20.dp)
            val menuWidthPx = with(density) { menuWidth.toPx() }
            val pad10 = with(density) { 10.dp.toPx() }
            val br = burgerRect
            AnimatedVisibility(
                visible = isMenuOpen && br != null,
                enter = fadeIn(tween(200)) + scaleIn(tween(250), initialScale = 0.98f, transformOrigin = TransformOrigin(0.9f, 0f)),
                exit = fadeOut(tween(150)),
                modifier = Modifier
                    .offset {
                        val r = br ?: Rect.Zero
                        IntOffset(
                            (r.right - menuWidthPx).coerceAtLeast(pad10).toInt(),
                            (r.bottom + pad10).toInt()
                        )
                    }
                    .width(menuWidth)
            ) {
                GlassMenuPanel {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 520.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        GlassLabel("ЦВЕТ НЕОНА", Modifier.padding(top = 4.dp))
                        NeonColorPicker()

                        GlassLabel("УМНЫЙ РЕЖИМ: КОНФИГИ")
                        GlassButton(
                            onClick = { showPromptDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("show_prompt_btn"),
                            height = 56.dp,
                            cornerRadius = 26.dp,
                            hueOffset = 10f
                        ) {
                            Text("👁 Показать промпт", fontSize = 14.sp, color = Color(0xFFF4F6FC), fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(6.dp))
                        GlassButton(
                            onClick = {
                                try {
                                    val text = context.resources.openRawResource(R.raw.config_prompt).bufferedReader().use { it.readText() }
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Config Prompt", text))
                                    Toast.makeText(context, "Промпт скопирован", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Ошибка чтения промпта", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("copy_prompt_btn"),
                            height = 56.dp,
                            cornerRadius = 26.dp,
                            hueOffset = 20f
                        ) {
                            Text("📋 Скопировать промпт", fontSize = 14.sp, color = Color(0xFFF4F6FC), fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(6.dp))
                        GlassButton(
                            onClick = { showGuideDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("show_guide_btn"),
                            height = 56.dp,
                            cornerRadius = 26.dp,
                            hueOffset = 30f
                        ) {
                            Text("📖 Инструкция", fontSize = 14.sp, color = Color(0xFFF4F6FC), fontWeight = FontWeight.Bold)
                        }
                        Text(
                            text = "Вставьте в нейросеть, дополните задачей и приложите скриншоты",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                        Spacer(Modifier.height(6.dp))
                        GlassButton(
                            onClick = {
                                if (isCheckingConfig) return@GlassButton
                                if (onCheckConfig != null) {
                                    isCheckingConfig = true
                                    isMenuOpen = false
                                    Toast.makeText(context, "Откройте нужный экран, снимок через 5 секунд", Toast.LENGTH_LONG).show()
                                    onCheckConfig { report ->
                                        isCheckingConfig = false
                                        checkReportDialog = report
                                    }
                                } else {
                                    Toast.makeText(context, "Проверка недоступна", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("check_config_btn"),
                            height = 56.dp,
                            cornerRadius = 26.dp,
                            hueOffset = -40f
                        ) {
                            Text(
                                if (isCheckingConfig) "⏳ Проверка…" else "🔍 Проверить конфиг сейчас",
                                fontSize = 14.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        GlassButton(
                            onClick = {
                                if (configList.size >= 5) {
                                    Toast.makeText(context, "Лимит 5 конфигов, удалите один", Toast.LENGTH_SHORT).show()
                                } else {
                                    zipPickerLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*"))
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("upload_config_zip_btn"),
                            isOn = true,
                            height = 56.dp,
                            cornerRadius = 26.dp,
                            hueOffset = 40f
                        ) {
                            Text("📁 Загрузить конфиг (zip)", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(10.dp))
                        GlassButton(
                            onClick = {
                                configManager.setActiveName("")
                                configManager.setMode(SmartConfigMode.DEFAULT_ONLY)
                                activeConfigName = ""
                                modeState = configManager.getMode()
                                Toast.makeText(context, "Включён встроенный конфиг по умолчанию", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            height = 56.dp,
                            cornerRadius = 26.dp,
                            hueOffset = 60f
                        ) {
                            Text("↺ Вернуть по умолчанию", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }

                        GlassLabel("ЗАГРУЖЕННЫЕ КОНФИГИ")
                        Text(
                            text = "Загружено: ${configList.size} из 5",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
                        )
                        if (configList.isEmpty()) {
                            Text(
                                text = "Нет сохранённых конфигов",
                                color = TextMuted,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (cfg in configList) {
                                    val isActive = activeConfigName == cfg
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(if (isActive) Color(0xFF1E3A4A).copy(alpha = 0.8f) else Color(0xFF141724).copy(alpha = 0.8f))
                                            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (isActive) "✔ $cfg" else cfg,
                                            color = if (isActive) Color(0xFF00E5FF) else Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            if (!isActive) {
                                                GlassButton(
                                                    onClick = {
                                                        configManager.setActiveName(cfg)
                                                        activeConfigName = cfg
                                                        modeState = configManager.getMode()
                                                    },
                                                    modifier = Modifier.width(84.dp),
                                                    height = 38.dp,
                                                    cornerRadius = 19.dp
                                                ) {
                                                    Text("Выбрать", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                            GlassButton(
                                                onClick = {
                                                    confirmDialogState = Triple(
                                                        "Удалить конфиг?",
                                                        "Удалить конфиг $cfg?"
                                                    ) {
                                                        val wasActive = activeConfigName == cfg
                                                        configManager.delete(cfg)
                                                        if (wasActive) {
                                                            configManager.setMode(SmartConfigMode.DEFAULT_ONLY)
                                                        }
                                                        configList = configManager.listConfigs()
                                                        activeConfigName = configManager.getActiveName()
                                                        modeState = configManager.getMode()
                                                    }
                                                },
                                                modifier = Modifier.width(44.dp),
                                                height = 38.dp,
                                                cornerRadius = 19.dp,
                                                hueOffset = -90f
                                            ) {
                                                Text("✕", fontSize = 13.sp, color = Color(0xFFFF8A80), fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // ----- БЛОК СБРОС -----
                        GlassLabel("СБРОС")
                        GlassButton(
                            onClick = {
                                confirmDialogState = Triple(
                                    "Вы уверены?",
                                    "Сбросить время цикла на 7 минут?"
                                ) { onResetCycleDelay() }
                            },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            cornerRadius = 22.dp,
                            hueOffset = -90f
                        ) {
                            Text("Сбросить таймер цикла", color = AccentRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(8.dp))
                        GlassButton(
                            onClick = {
                                confirmDialogState = Triple(
                                    "Вы уверены?",
                                    "Сбросить яркость неона на 85%?"
                                ) { onResetNeonBrightness() }
                            },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            cornerRadius = 22.dp,
                            hueOffset = -90f
                        ) {
                            Text("Сбросить яркость неона", color = AccentRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(8.dp))
                        GlassButton(
                            onClick = {
                                confirmDialogState = Triple(
                                    "Вы уверены?",
                                    "Удалить все загруженные пользовательские конфиги?"
                                ) {
                                    onDeleteAllCustomConfigs()
                                    configList = configManager.listConfigs()
                                    activeConfigName = configManager.getActiveName()
                                    modeState = configManager.getMode()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            cornerRadius = 22.dp,
                            hueOffset = -90f
                        ) {
                            Text("Удалить загруженные конфиги", color = AccentRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(8.dp))
                        GlassButton(
                            onClick = {
                                confirmDialogState = Triple(
                                    "Вы уверены?",
                                    "Сбросить все точки, свайпы и записанный макрос?"
                                ) { onResetActions() }
                            },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            cornerRadius = 22.dp,
                            hueOffset = -90f
                        ) {
                            Text("Сбросить точки, свайпы и запись", color = AccentRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(8.dp))
                        GlassButton(
                            onClick = {
                                confirmDialogState = Triple(
                                    "Вы уверены?",
                                    "Сбросить абсолютно ВСЕ настройки автокликера?"
                                ) {
                                    onResetAll()
                                    configList = configManager.listConfigs()
                                    activeConfigName = configManager.getActiveName()
                                    modeState = configManager.getMode()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            cornerRadius = 24.dp,
                            hueOffset = -110f
                        ) {
                            Text("Сбросить ВСЁ", color = Color(0xFFFF5252), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }

                        // ----- БЛОК ЖУРНАЛ СОБЫТИЙ В МЕНЮ -----
                        GlassLabel("ЖУРНАЛ СОБЫТИЙ")
                        val menuLogFiles = remember(logs) { com.example.autoclicker.data.LogFileManager.listLogFiles() }
                        val menuLogSize = remember(logs) { com.example.autoclicker.data.LogFileManager.totalSizeBytes() }
                        val menuSizeKb = menuLogSize / 1024
                        Text(
                            text = "Файлов логов: ${menuLogFiles.size}, размер $menuSizeKb КБ, хранятся 7 суток",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            GlassButton(
                                onClick = {
                                    val ok = com.example.autoclicker.data.LogExporter.copyToClipboard(context)
                                    if (!ok) Toast.makeText(context, "Логов нет", Toast.LENGTH_SHORT).show()
                                    else Toast.makeText(context, "Логи скопированы", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f),
                                height = 36.dp,
                                cornerRadius = 16.dp,
                                hueOffset = 10f
                            ) {
                                Text("Копия", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            GlassButton(
                                onClick = {
                                    val name = com.example.autoclicker.data.LogExporter.saveToDownloads(context)
                                    if (name != null) {
                                        Toast.makeText(context, "Сохранено в Загрузки: $name", Toast.LENGTH_LONG).show()
                                    } else {
                                        val share = com.example.autoclicker.data.LogExporter.createShareIntent(context)
                                        if (share != null) context.startActivity(share)
                                        else Toast.makeText(context, "Логов нет", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                height = 36.dp,
                                cornerRadius = 16.dp,
                                hueOffset = 25f
                            ) {
                                Text("Скачать", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            GlassButton(
                                onClick = {
                                    val share = com.example.autoclicker.data.LogExporter.createShareIntent(context)
                                    if (share != null) context.startActivity(share)
                                    else Toast.makeText(context, "Логов нет", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f),
                                height = 36.dp,
                                cornerRadius = 16.dp,
                                hueOffset = 40f
                            ) {
                                Text("Поделиться", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            GlassButton(
                                onClick = {
                                    confirmDialogState = Triple(
                                        "Вы уверены?",
                                        "Очистить все файлы логов и события?"
                                    ) { onClearLogs() }
                                },
                                modifier = Modifier.weight(1f),
                                height = 36.dp,
                                cornerRadius = 16.dp,
                                hueOffset = -60f
                            ) {
                                Text("Очистить", color = AccentRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- Иконки для слайдеров (рисуются цветом неона) ----------
@Composable
private fun ClockIcon() {
    val hue = LocalNeonHue.current
    Canvas(Modifier.size(26.dp)) {
        val c = nc(hue.value, 0f, 1f, 0.85f)
        val s = 2f * density
        drawCircle(c, radius = size.minDimension * 0.40f, style = Stroke(s))
        drawLine(c, center, Offset(center.x, center.y - size.height * 0.22f), s, StrokeCap.Round)
        drawLine(c, center, Offset(center.x + size.width * 0.16f, center.y + size.height * 0.10f), s, StrokeCap.Round)
    }
}

@Composable
private fun SunIcon() {
    val hue = LocalNeonHue.current
    Canvas(Modifier.size(26.dp)) {
        val c = nc(hue.value, 0f, 1f, 0.85f)
        val s = 2f * density
        val r = size.minDimension * 0.16f
        drawCircle(c, radius = r, style = Stroke(s))
        for (i in 0 until 8) {
            val a = i * Math.PI / 4.0
            val ca = Math.cos(a).toFloat()
            val sa = Math.sin(a).toFloat()
            val r1 = size.minDimension * 0.28f
            val r2 = size.minDimension * 0.42f
            drawLine(c, Offset(center.x + ca * r1, center.y + sa * r1), Offset(center.x + ca * r2, center.y + sa * r2), s, StrokeCap.Round)
        }
    }
}
