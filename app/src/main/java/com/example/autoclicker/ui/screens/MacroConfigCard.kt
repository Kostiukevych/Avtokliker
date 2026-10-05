package com.example.autoclicker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.autoclicker.data.RecordedMacro
import com.example.autoclicker.ui.theme.AccentGreen
import com.example.autoclicker.ui.theme.AccentRed
import com.example.autoclicker.ui.theme.GlassButton
import com.example.autoclicker.ui.theme.GlassPanel
import com.example.autoclicker.ui.theme.GlassSlider
import com.example.autoclicker.ui.theme.TextMuted
import com.example.autoclicker.ui.theme.TextSecondary

@Composable
fun MacroConfigContent(
    recordedMacro: RecordedMacro?,
    repeatCount: Int,
    intervalSec: Int,
    isMacroRunning: Boolean,
    isAccessibilityConnected: Boolean,
    onStartMacro: () -> Unit,
    onStopMacro: () -> Unit,
    onStartRecording: () -> Unit,
    onClearMacro: () -> Unit,
    onUpdateConfig: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Информация о макросе
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF141724).copy(alpha = 0.85f), Color(0xFF090A12).copy(alpha = 0.95f))
                    )
                )
                .padding(12.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "СОСТОЯНИЕ МАКРОСА:",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (isMacroRunning) {
                        Text(
                            text = "▶ ВЫПОЛНЯЕТСЯ",
                            color = AccentGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                if (recordedMacro != null && recordedMacro.isNotEmpty) {
                    Text(
                        text = "Событий: ${recordedMacro.rawEventsCount}  •  Жестов: ${recordedMacro.strokes.size}  •  Групп: ${recordedMacro.multitouchGroups.size}",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "Длительность: ${recordedMacro.formattedDuration}",
                        color = Color(0xFF8CFF00),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                } else {
                    Text(
                        text = "Макрос не записан. Нажмите «Запись», чтобы перехватить мультитач-жесты.",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Повторы макроса
        Text(
            text = "ПОВТОРОВ МАКРОСА",
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )
        GlassSlider(
            value = repeatCount.toFloat(),
            onValueChange = { onUpdateConfig(it.toInt(), intervalSec) },
            valueRange = 1f..15f,
            steps = 13,
            formattedValue = "${repeatCount}×",
            icon = {
                Text("🔁", color = Color.White, fontSize = 14.sp)
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Пауза между повторами (сек)
        Text(
            text = "ПАУЗА МЕЖДУ ПОВТОРАМИ",
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )
        GlassSlider(
            value = intervalSec.toFloat(),
            onValueChange = { onUpdateConfig(repeatCount, it.toInt()) },
            valueRange = 0f..30f,
            steps = 29,
            formattedValue = "${intervalSec} с",
            icon = {
                Text("⏱", color = Color.White, fontSize = 14.sp)
            }
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Кнопка записи жестов поверх экрана
        GlassButton(
            onClick = onStartRecording,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("record_macro_btn"),
            height = 48.dp,
            isOn = true
        ) {
            Text(
                text = if (recordedMacro != null && recordedMacro.isNotEmpty) "● Перезаписать жесты" else "● Начать запись жестов",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Кнопки Воспроизвести / Стоп / Очистить
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val hasMacro = recordedMacro != null && recordedMacro.isNotEmpty
            GlassButton(
                onClick = { if (isMacroRunning) onStopMacro() else onStartMacro() },
                modifier = Modifier
                    .weight(1f)
                    .testTag("play_macro_btn"),
                height = 42.dp,
                isOn = hasMacro && !isMacroRunning
            ) {
                Text(
                    text = if (isMacroRunning) "■ Стоп" else "▶ Старт",
                    color = if (hasMacro) Color.White else TextMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }

            var showDeleteConfirm by remember { mutableStateOf(false) }

            if (hasMacro) {
                GlassButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("clear_macro_btn"),
                    height = 42.dp
                ) {
                    Text(
                        text = "Удалить макрос",
                        color = AccentRed,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                }
            }

            if (showDeleteConfirm) {
                com.example.autoclicker.ui.theme.GlassDialog(
                    title = "Вы уверены?",
                    onDismissRequest = { showDeleteConfirm = false },
                    onConfirm = {
                        showDeleteConfirm = false
                        onClearMacro()
                    },
                    confirmText = "Удалить",
                    cancelText = "Отмена"
                ) {
                    Text(
                        text = "Записанный макрос будет полностью удалён.",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
fun MacroConfigCard(
    recordedMacro: RecordedMacro?,
    repeatCount: Int,
    intervalSec: Int,
    isMacroRunning: Boolean,
    isAccessibilityConnected: Boolean,
    onStartMacro: () -> Unit,
    onStopMacro: () -> Unit,
    onStartRecording: () -> Unit,
    onClearMacro: () -> Unit,
    onUpdateConfig: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    GlassPanel(
        modifier = modifier
            .fillMaxWidth()
            .testTag("macro_config_card")
    ) {
        MacroConfigContent(
            recordedMacro = recordedMacro,
            repeatCount = repeatCount,
            intervalSec = intervalSec,
            isMacroRunning = isMacroRunning,
            isAccessibilityConnected = isAccessibilityConnected,
            onStartMacro = onStartMacro,
            onStopMacro = onStopMacro,
            onStartRecording = onStartRecording,
            onClearMacro = onClearMacro,
            onUpdateConfig = onUpdateConfig
        )
    }
}
