package com.example.autoclicker.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.autoclicker.R
import com.example.autoclicker.data.ClickerSettings
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.data.SmartConfigManager
import com.example.autoclicker.data.SmartConfigMode
import com.example.autoclicker.engine.CycleStatus
import com.example.autoclicker.ui.theme.AccentAmber
import com.example.autoclicker.ui.theme.AccentGreen
import com.example.autoclicker.ui.theme.AccentRed
import com.example.autoclicker.ui.theme.GlassButton
import com.example.autoclicker.ui.theme.GlassDialog
import com.example.autoclicker.ui.theme.GlassPanel
import com.example.autoclicker.ui.theme.GlassPointButton
import com.example.autoclicker.ui.theme.GlassSlider
import com.example.autoclicker.ui.theme.GlassSwitch
import com.example.autoclicker.ui.theme.LiquidReservoir
import com.example.autoclicker.ui.theme.LocalNeonBrightness
import com.example.autoclicker.ui.theme.LocalNeonHue
import com.example.autoclicker.ui.theme.NeonTheme
import com.example.autoclicker.ui.theme.PrimaryBlue
import com.example.autoclicker.ui.theme.TextMuted
import com.example.autoclicker.ui.theme.TextPrimary
import com.example.autoclicker.ui.theme.TextSecondary
import com.example.autoclicker.ui.theme.liquidGlassBackground
import com.example.autoclicker.ui.theme.neonGlow

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
    onUpdateNeonBrightness: (Int) -> Unit = {
        SettingsRepository.getInstance(it as? Context ?: error("")).updateNeonBrightness(85)
    },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository.getInstance(context) }
    val configManager = remember { SmartConfigManager.getInstance(context) }

    // 1. Неон: плавный проход всего спектра (0..360) за 36 секунд через один общий rememberInfiniteTransition
    val infiniteTransition = rememberInfiniteTransition(label = "neonCycle")
    val neonHue by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 36000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "neonHue"
    )

    // Обновляем глобальный NeonTheme для сервисов
    LaunchedEffect(neonHue) {
        NeonTheme.currentHue = neonHue
    }

    val neonBrightness = settings.neonBrightness
    val neonBriFraction = neonBrightness / 100f

    // Состояния диалогов настроек
    var editingPointId by remember { mutableStateOf<Int?>(null) }
    var editingSwipeId by remember { mutableStateOf<Int?>(null) }
    var isMacroDialogVisible by remember { mutableStateOf(false) }

    // Состояние бокового бургер-меню
    var isDrawerOpen by remember { mutableStateOf(false) }

    // Состояние конфигов
    var activeConfigName by remember { mutableStateOf(configManager.getActiveName()) }
    var configList by remember { mutableStateOf(configManager.listConfigs()) }
    var newlyImportedConfig by remember { mutableStateOf<String?>(null) }

    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val result = configManager.importZip(uri)
            result.onSuccess { configName ->
                configList = configManager.listConfigs()
                newlyImportedConfig = configName
            }.onFailure { err ->
                Toast.makeText(context, err.message ?: "Ошибка импорта", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Диалог после импорта конфига
    if (newlyImportedConfig != null) {
        val importedName = newlyImportedConfig!!
        AlertDialog(
            onDismissRequest = { newlyImportedConfig = null },
            title = { Text("Применить сейчас?") },
            text = { Text("Конфиг '$importedName' успешно загружен. Какой режим включить?") },
            confirmButton = {
                Button(
                    onClick = {
                        configManager.setActiveName(importedName)
                        configManager.setMode(SmartConfigMode.CUSTOM_ONLY)
                        activeConfigName = importedName
                        newlyImportedConfig = null
                        Toast.makeText(context, "Применён режим «Только мой»", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Только мой")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = {
                            configManager.setActiveName(importedName)
                            configManager.setMode(SmartConfigMode.MERGED)
                            activeConfigName = importedName
                            newlyImportedConfig = null
                            Toast.makeText(context, "Применён режим «Встроенный + мой»", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("Встроенный + мой")
                    }
                    OutlinedButton(
                        onClick = { newlyImportedConfig = null }
                    ) {
                        Text("Позже")
                    }
                }
            }
        )
    }

    CompositionLocalProvider(
        LocalNeonHue provides neonHue,
        LocalNeonBrightness provides neonBriFraction
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .liquidGlassBackground()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                // ===== РЕЗЕРВУАР (верх экрана, 64dp) =====
                LiquidReservoir(
                    cycleDelayMinutes = settings.cycleDelayMinutes,
                    neonBrightness = neonBrightness
                )

                Spacer(modifier = Modifier.height(10.dp))

                // ===== ГЛАВНАЯ СТЕКЛЯННАЯ ПАНЕЛЬ =====
                GlassPanel(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 24.dp
                ) {
                    // Хедер: Заголовок и Бургер
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Автокликер",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        // Бургер-кнопка
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(Color.White.copy(alpha = 0.40f), Color.White.copy(alpha = 0.08f))
                                    )
                                )
                                .neonGlow(
                                    hueProvider = { neonHue },
                                    brightnessProvider = { neonBriFraction },
                                    cornerRadius = 14.dp,
                                    strokeWidth = 1.8.dp
                                )
                                .clickable { isDrawerOpen = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(modifier = Modifier.size(width = 18.dp, height = 2.dp).background(Color.White))
                                Box(modifier = Modifier.size(width = 18.dp, height = 2.dp).background(Color.White))
                                Box(modifier = Modifier.size(width = 18.dp, height = 2.dp).background(Color.White))
                            }
                        }
                    }

                    // СЕКЦИЯ: РАЗРЕШЕНИЯ
                    SectionTitle("РАЗРЕШЕНИЯ")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        GlassButton(
                            onClick = onOpenOverlaySettings,
                            modifier = Modifier.weight(1f),
                            isOn = isOverlayGranted
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Поверх экрана", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text("SYSTEM_ALERT", fontSize = 9.sp, color = if (isOverlayGranted) Color(0xFFC8FF64) else TextSecondary)
                            }
                        }

                        GlassButton(
                            onClick = onOpenAccessibilitySettings,
                            modifier = Modifier.weight(1f),
                            isOn = isAccessibilityConnected
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Accessibility", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text("для нажатий", fontSize = 9.sp, color = if (isAccessibilityConnected) Color(0xFFC8FF64) else TextSecondary)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // СЕКЦИЯ: ВРЕМЯ ЦИКЛА
                    SectionTitle("ВРЕМЯ ЦИКЛА")
                    GlassSlider(
                        value = settings.cycleDelayMinutes.toFloat(),
                        onValueChange = { onUpdateCycleDelay(it.toInt()) },
                        valueRange = 1f..15f,
                        steps = 13,
                        formattedValue = "${settings.cycleDelayMinutes} мин",
                        icon = {
                            Text("⏱", color = Color.White, fontSize = 15.sp)
                        }
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // СЕКЦИЯ: РЕЖИМ
                    SectionTitle("РЕЖИМ")
                    val isApi30 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    GlassSwitch(
                        text = if (!isApi30) "Умный режим (нужен Android 11+)" else "Умный режим",
                        checked = settings.isSmartMode && isApi30,
                        onCheckedChange = { if (isApi30) onUpdateSmartMode(it) },
                        modifier = Modifier.testTag("smart_mode_switch")
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    GlassSwitch(
                        text = "Сохранять снимки (отладка)",
                        checked = settings.isDebugScreenshots,
                        onCheckedChange = onUpdateDebugScreenshots,
                        modifier = Modifier.testTag("debug_screenshots_switch")
                    )

                    // Название активного конфига
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Активный конфиг: ${if (activeConfigName.isNotBlank()) activeConfigName else "Встроенный (default)"}",
                        fontSize = 11.sp,
                        color = Color(0xFF80D8FF),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 4.dp)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // СЕКЦИЯ: ПОИНТЫ (ТОЧКИ НАЖАТИЯ) - 5 в ряд (1..10)
                    SectionTitle("ПОИНТЫ (ТОЧКИ НАЖАТИЯ)")
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (id in 1..5) {
                                val p = settings.getPointById(id)
                                GlassPointButton(
                                    id = id,
                                    enabled = p.enabled,
                                    onClick = { editingPointId = id },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (id in 6..10) {
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

                    Spacer(modifier = Modifier.height(14.dp))

                    // СЕКЦИЯ: СВАЙПЫ (3 в ряд)
                    SectionTitle("СВАЙПЫ")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (i in 1..3) {
                            val sw = settings.swipes.find { it.id == i }
                            val isSwOn = sw?.enabled == true && settings.isSwipesEnabled
                            GlassButton(
                                onClick = { editingSwipeId = i },
                                modifier = Modifier.weight(1f),
                                height = 48.dp,
                                isOn = isSwOn
                            ) {
                                Text("Свайп $i", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // СЕКЦИЯ: МАКРОС / ЗАПИСЬ
                    SectionTitle("МАКРОС / ЗАПИСЬ")
                    GlassButton(
                        onClick = { isMacroDialogVisible = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btnRecord"),
                        height = 56.dp,
                        isOn = settings.recordedMacro != null && settings.recordedMacro.isNotEmpty
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("● Запись макроса", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("нажатия, жесты, зажимы", fontSize = 10.sp, color = TextSecondary)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // СЕКЦИЯ: ЯРКОСТЬ НЕОНА
                    SectionTitle("ЯРКОСТЬ НЕОНА")
                    GlassSlider(
                        value = neonBrightness.toFloat(),
                        onValueChange = {
                            val b = it.toInt()
                            settingsRepo.updateNeonBrightness(b)
                        },
                        valueRange = 0f..100f,
                        steps = 0,
                        formattedValue = "$neonBrightness%",
                        icon = {
                            Text("💡", color = Color.White, fontSize = 15.sp)
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // СЕКЦИЯ: УПРАВЛЕНИЕ ЦИКЛОМ (START / STOP)
                    SectionTitle("УПРАВЛЕНИЕ ЦИКЛОМ")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
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

                    Spacer(modifier = Modifier.height(6.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0F101A).copy(alpha = 0.85f))
                            .padding(8.dp)
                    ) {
                        Column {
                            Text("Текущее: $currentAction", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Text("Следующее: $nextAction", color = Color(0xFF80D8FF), fontSize = 11.sp)
                            Text("Последнее: $lastAction", color = TextMuted, fontSize = 10.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val isRunning = cycleStatus != CycleStatus.STOPPED
                        GlassButton(
                            onClick = onStartCycle,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("start_cycle_btn"),
                            height = 48.dp,
                            isOn = isRunning
                        ) {
                            Text(
                                text = if (isRunning) "▶ ЗАПУЩЕНО" else "START",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }

                        GlassButton(
                            onClick = onStopCycle,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("stop_cycle_btn"),
                            height = 48.dp
                        ) {
                            Text("STOP", color = AccentRed, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }

                    if (isOverlayGranted) {
                        Spacer(modifier = Modifier.height(10.dp))
                        GlassButton(
                            onClick = onLaunchOverlayService,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("open_floating_window_btn"),
                            height = 44.dp
                        ) {
                            Text("Открыть плавающее окно поверх игр", fontSize = 12.sp, color = Color(0xFF80D8FF), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // ===== ЖУРНАЛ СОБЫТИЙ =====
                EventLogView(logs = logs, onClearLogs = onClearLogs)

                Spacer(modifier = Modifier.height(20.dp))
            }

            // ===== ДИАЛОГ НАСТРОЙКИ ТОЧКИ =====
            if (editingPointId != null) {
                val ptId = editingPointId!!
                val pt = settings.getPointById(ptId)
                GlassDialog(
                    title = "Поинт $ptId",
                    onDismissRequest = { editingPointId = null },
                    onConfirm = { editingPointId = null },
                    confirmText = "Готово",
                    cancelText = "Отмена"
                ) {
                    PointConfigContent(
                        point = pt,
                        onTestClick = { onTestClick(ptId) },
                        onToggleEnabled = { en ->
                            onUpdatePointConfig(ptId, en, pt.clickCount, pt.intervalSec)
                        },
                        onCountChange = { cnt ->
                            onUpdatePointConfig(ptId, pt.enabled, cnt, pt.intervalSec)
                        },
                        onIntervalChange = { intv ->
                            onUpdatePointConfig(ptId, pt.enabled, pt.clickCount, intv)
                        }
                    )
                }
            }

            // ===== ДИАЛОГ НАСТРОЙКИ СВАЙПА =====
            if (editingSwipeId != null) {
                val swId = editingSwipeId!!
                val sw = settings.swipes.find { it.id == swId } ?: com.example.autoclicker.data.SwipeAction(id = swId)
                GlassDialog(
                    title = "Свайп $swId",
                    onDismissRequest = { editingSwipeId = null },
                    onConfirm = { editingSwipeId = null },
                    confirmText = "Готово",
                    cancelText = "Отмена"
                ) {
                    SingleSwipeContent(
                        swipe = sw,
                        onToggleEnabled = { en ->
                            onUpdateSwipeConfig(swId, en, sw.durationMs, sw.intervalSec)
                        },
                        onDurationChange = { dur ->
                            onUpdateSwipeConfig(swId, sw.enabled, dur, sw.intervalSec)
                        },
                        onIntervalChange = { intv ->
                            onUpdateSwipeConfig(swId, sw.enabled, sw.durationMs, intv)
                        },
                        onTestSwipe = { onTestSwipe(swId) }
                    )
                }
            }

            // ===== ДИАЛОГ НАСТРОЙКИ МАКРОСА =====
            if (isMacroDialogVisible) {
                GlassDialog(
                    title = "Макрос / запись",
                    onDismissRequest = { isMacroDialogVisible = false },
                    onConfirm = { isMacroDialogVisible = false },
                    confirmText = "Готово",
                    cancelText = "Отмена"
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
                        onClearMacro = onClearMacro,
                        onUpdateConfig = onUpdateMacroConfig
                    )
                }
            }

            // ===== БУРГЕР-МЕНЮ (DRAWER СЛЕВА) =====
            if (isDrawerOpen) {
                // Затемнение фона
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { isDrawerOpen = false }
                        )
                )

                // Выдвижная боковая панель слева
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(300.dp)
                        .clip(RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF141428).copy(alpha = 0.98f), Color(0xFF080812).copy(alpha = 0.99f))
                            )
                        )
                        .neonGlow(
                            hueProvider = { neonHue },
                            brightnessProvider = { neonBriFraction },
                            cornerRadius = 24.dp,
                            strokeWidth = 2.dp
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {} // Consume click
                        )
                        .padding(18.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        // Заголовок Меню и крестик
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Меню",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )

                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.12f))
                                    .clickable { isDrawerOpen = false },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("✕", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Блок: «Умный режим: конфиги»
                        SectionTitle("УМНЫЙ РЕЖИМ: КОНФИГИ")

                        // 1. Кнопка «Скопировать промпт для нейросети»
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
                            height = 44.dp
                        ) {
                            Text("📋 Скопировать промпт", fontSize = 12.sp, color = Color(0xFF80D8FF), fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Подсказка
                        Text(
                            text = "Вставьте в нейросеть, дополните задачей и приложите скриншоты",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // 2. Кнопка «Загрузить конфиг (zip)»
                        GlassButton(
                            onClick = {
                                zipPickerLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*"))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("upload_config_zip_btn"),
                            height = 44.dp,
                            isOn = true
                        ) {
                            Text("📁 Загрузить конфиг (zip)", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // 3. Кнопка «Вернуть по умолчанию»
                        GlassButton(
                            onClick = {
                                configManager.setActiveName("")
                                configManager.setMode(SmartConfigMode.DEFAULT_ONLY)
                                activeConfigName = ""
                                Toast.makeText(context, "Включён встроенный конфиг по умолчанию", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            height = 44.dp
                        ) {
                            Text("↺ Вернуть по умолчанию", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Список загруженных пользовательских конфигов
                        SectionTitle("ЗАГРУЖЕННЫЕ КОНФИГИ")
                        if (configList.isEmpty()) {
                            Text(
                                text = "Нет сохранённых конфигов",
                                color = TextMuted,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (cfg in configList) {
                                    val isActive = activeConfigName == cfg
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (isActive) Color(0xFF1E3A4A) else Color(0xFF141724))
                                            .padding(8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (isActive) "✔ $cfg" else cfg,
                                            color = if (isActive) Color(0xFF00E5FF) else Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier.weight(1f)
                                        )

                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            if (!isActive) {
                                                GlassButton(
                                                    onClick = {
                                                        configManager.setActiveName(cfg)
                                                        activeConfigName = cfg
                                                    },
                                                    height = 28.dp,
                                                    cornerRadius = 8.dp
                                                ) {
                                                    Text("Выбрать", fontSize = 10.sp, color = Color.White, modifier = Modifier.padding(horizontal = 6.dp))
                                                }
                                            }

                                            GlassButton(
                                                onClick = {
                                                    configManager.delete(cfg)
                                                    configList = configManager.listConfigs()
                                                    activeConfigName = configManager.getActiveName()
                                                },
                                                height = 28.dp,
                                                cornerRadius = 8.dp
                                            ) {
                                                Text("✕", fontSize = 11.sp, color = AccentRed, modifier = Modifier.padding(horizontal = 6.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        color = Color.White.copy(alpha = 0.55f),
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}
