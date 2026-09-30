package com.example.autoclicker.ui.screens

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.autoclicker.data.ClickerSettings
import com.example.autoclicker.engine.CycleStatus
import com.example.autoclicker.ui.theme.AccentAmber
import com.example.autoclicker.ui.theme.AccentGreen
import com.example.autoclicker.ui.theme.AccentRed
import com.example.autoclicker.ui.theme.DarkBackground
import com.example.autoclicker.ui.theme.PrimaryBlue
import com.example.autoclicker.ui.theme.SurfaceDark
import com.example.autoclicker.ui.theme.TextMuted
import com.example.autoclicker.ui.theme.TextPrimary
import com.example.autoclicker.ui.theme.TextSecondary

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
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DarkBackground
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Заголовок приложения
            Text(
                text = "AutoClicker",
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Автоматизация нажатий по экранным координатам",
                color = TextSecondary,
                fontSize = 13.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Блок статуса системных разрешений
            PermissionsStatusCard(
                isAccessibilityConnected = isAccessibilityConnected,
                isOverlayGranted = isOverlayGranted,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                onOpenOverlaySettings = onOpenOverlaySettings,
                onLaunchOverlayService = onLaunchOverlayService
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Блок управления START / STOP
            CycleControlCard(
                cycleStatus = cycleStatus,
                currentAction = currentAction,
                lastAction = lastAction,
                nextAction = nextAction,
                countdownText = countdownText,
                cycleNumber = cycleNumber,
                isAccessibilityConnected = isAccessibilityConnected,
                onStartCycle = onStartCycle,
                onStopCycle = onStopCycle
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Секция задержки между циклами
            CycleDelayConfigCard(
                delayMinutes = settings.cycleDelayMinutes,
                onUpdateCycleDelay = onUpdateCycleDelay
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Точки нажатий
            Text(
                text = "ТОЧКИ НАЖАТИЯ (POINTS)",
                color = PrimaryBlue,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )

            PointConfigCard(
                point = settings.point1,
                onTestClick = { onTestClick(1) },
                onToggleEnabled = { enabled ->
                    onUpdatePointConfig(1, enabled, settings.point1.clickCount, settings.point1.intervalSec)
                },
                onCountChange = { count ->
                    onUpdatePointConfig(1, settings.point1.enabled, count, settings.point1.intervalSec)
                },
                onIntervalChange = { interval ->
                    onUpdatePointConfig(1, settings.point1.enabled, settings.point1.clickCount, interval)
                }
            )

            PointConfigCard(
                point = settings.point2,
                onTestClick = { onTestClick(2) },
                onToggleEnabled = { enabled ->
                    onUpdatePointConfig(2, enabled, settings.point2.clickCount, settings.point2.intervalSec)
                },
                onCountChange = { count ->
                    onUpdatePointConfig(2, settings.point2.enabled, count, settings.point2.intervalSec)
                },
                onIntervalChange = { interval ->
                    onUpdatePointConfig(2, settings.point2.enabled, settings.point2.clickCount, interval)
                }
            )

            PointConfigCard(
                point = settings.point3,
                onTestClick = { onTestClick(3) },
                onToggleEnabled = { enabled ->
                    onUpdatePointConfig(3, enabled, settings.point3.clickCount, settings.point3.intervalSec)
                },
                onCountChange = { count ->
                    onUpdatePointConfig(3, settings.point3.enabled, count, settings.point3.intervalSec)
                },
                onIntervalChange = { interval ->
                    onUpdatePointConfig(3, settings.point3.enabled, settings.point3.clickCount, interval)
                }
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Лог последних событий
            EventLogView(
                logs = logs,
                onClearLogs = onClearLogs
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PermissionsStatusCard(
    isAccessibilityConnected: Boolean,
    isOverlayGranted: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onLaunchOverlayService: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, Color(0xFF2E3346))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Accessibility
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(10.dp)
                            .height(10.dp)
                            .background(
                                if (isAccessibilityConnected) AccentGreen else AccentRed,
                                RoundedCornerShape(5.dp)
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isAccessibilityConnected) "Accessibility: Активен" else "Accessibility: Не включен",
                        color = TextPrimary,
                        fontSize = 13.sp
                    )
                }

                if (!isAccessibilityConnected) {
                    Button(
                        onClick = onOpenAccessibilitySettings,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Включить", fontSize = 11.sp)
                    }
                }
            }

            if (!isAccessibilityConnected) {
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2B1C1C), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = "Включите службу AutoClicker в настройках специальных возможностей. На Android 13 и новее, если переключатель серый: Настройки → Приложения → AutoClicker → меню ⋮ → Разрешить ограниченные настройки, затем вернитесь и включите службу.",
                        color = Color(0xFFFF8A80),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Overlay
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(10.dp)
                            .height(10.dp)
                            .background(
                                if (isOverlayGranted) AccentGreen else AccentAmber,
                                RoundedCornerShape(5.dp)
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isOverlayGranted) "Overlay: Разрешен" else "Overlay: Нет разрешения",
                        color = TextPrimary,
                        fontSize = 13.sp
                    )
                }

                if (!isOverlayGranted) {
                    Button(
                        onClick = onOpenOverlaySettings,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentAmber),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Разрешить", fontSize = 11.sp, color = Color.Black)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Данные экрана и системы координат
            val context = LocalContext.current
            val wm = remember(context) { context.getSystemService(Context.WINDOW_SERVICE) as WindowManager }
            val (w, h, dpi, orient) = remember(context) {
                val isLand = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                val oStr = if (isLand) "Landscape" else "Portrait"
                val densityDpi = context.resources.displayMetrics.densityDpi
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bounds = wm.maximumWindowMetrics.bounds
                    listOf(bounds.width(), bounds.height(), densityDpi, oStr)
                } else {
                    val m = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    wm.defaultDisplay.getRealMetrics(m)
                    listOf(m.widthPixels, m.heightPixels, densityDpi, oStr)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF141720), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Text(
                    text = "Screen:",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "$w x $h ($orient, $dpi dpi)",
                    color = Color(0xFF80CBC4),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            if (isOverlayGranted) {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onLaunchOverlayService,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("open_floating_window_btn"),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Открыть плавающее окно поверх игр", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun CycleControlCard(
    cycleStatus: CycleStatus,
    currentAction: String,
    lastAction: String,
    nextAction: String,
    countdownText: String,
    cycleNumber: Int,
    isAccessibilityConnected: Boolean,
    onStartCycle: () -> Unit,
    onStopCycle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, Color(0xFF2E3346))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val cycleInfo = if (cycleNumber > 0) " (Цикл #$cycleNumber)" else ""
                Text(
                    text = "СТАТУС: ${cycleStatus.name}$cycleInfo",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
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
                        fontSize = 15.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF141720), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Text(
                    text = "Текущее: $currentAction",
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Следующее: $nextAction",
                    color = PrimaryBlue,
                    fontSize = 11.sp
                )
                Text(
                    text = "Последнее: $lastAction",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val isRunning = cycleStatus != CycleStatus.STOPPED
                Button(
                    onClick = onStartCycle,
                    enabled = isAccessibilityConnected && !isRunning,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("start_cycle_btn"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentGreen,
                        disabledContainerColor = if (isRunning) Color(0xFF00B0FF) else Color(0xFF2B3A30),
                        disabledContentColor = if (isRunning) Color.White else Color(0xFF78909C)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (isRunning) "▶ ЗАПУЩЕНО" else "START",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                Button(
                    onClick = onStopCycle,
                    enabled = cycleStatus != CycleStatus.STOPPED,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("stop_cycle_btn"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentRed,
                        disabledContainerColor = Color(0xFF3A2B2B)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("STOP", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun CycleDelayConfigCard(
    delayMinutes: Int,
    onUpdateCycleDelay: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, Color(0xFF2E3346))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "ЗАДЕРЖКА МЕЖДУ ЦИКЛАМИ",
                color = PrimaryBlue,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Задержка: $delayMinutes минут",
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Slider(
                value = delayMinutes.toFloat(),
                onValueChange = { onUpdateCycleDelay(it.toInt()) },
                valueRange = 1f..15f,
                steps = 13
            )

            Text(
                text = "Каждые $delayMinutes минут приложение автоматически выполняет последовательность Point 1 -> Point 2 -> Point 3. Не зависит от игры и продолжительности матча.",
                color = TextSecondary,
                fontSize = 11.sp
            )
        }
    }
}
