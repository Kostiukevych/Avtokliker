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
import com.example.autoclicker.data.ClickPoint
import com.example.autoclicker.ui.theme.AccentRed
import com.example.autoclicker.ui.theme.GlassButton
import com.example.autoclicker.ui.theme.GlassPanel
import com.example.autoclicker.ui.theme.GlassSlider
import com.example.autoclicker.ui.theme.GlassSwitch
import com.example.autoclicker.ui.theme.TextMuted
import com.example.autoclicker.ui.theme.TextSecondary

@Composable
fun PointConfigContent(
    point: ClickPoint,
    onTestClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onCountChange: (Int) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onResetPoint: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Тумблер включения точки
        GlassSwitch(
            text = "Поинт ${point.id} включён",
            checked = point.enabled,
            onCheckedChange = onToggleEnabled,
            modifier = Modifier.testTag("point_${point.id}_switch")
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Отображение координат
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
                Text(
                    text = "КООРДИНАТЫ:",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (point.isConfigured) {
                    Text(
                        text = "X = ${point.x.toInt()}    Y = ${point.y.toInt()}",
                        color = Color(0xFF8CFF00),
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                } else {
                    Text(
                        text = "Не заданы (используйте плавающее окно)",
                        color = AccentRed,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Количество нажатий
        Text(
            text = "КОЛИЧЕСТВО НАЖАТИЙ",
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )
        GlassSlider(
            value = point.clickCount.toFloat(),
            onValueChange = { onCountChange(it.toInt()) },
            valueRange = 1f..10f,
            steps = 8,
            formattedValue = "${point.clickCount}×",
            icon = {
                Text("×", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Интервал между нажатиями
        Text(
            text = "ИНТЕРВАЛ МЕЖДУ НАЖАТИЯМИ",
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )
        GlassSlider(
            value = point.intervalSec.toFloat(),
            onValueChange = { onIntervalChange(it.toInt()) },
            valueRange = 1f..300f,
            steps = 0,
            formattedValue = run {
            val s = point.intervalSec
            when {
                s < 60 -> "$s с"
                s % 60 == 0 -> "${s / 60} мин"
                else -> "${s / 60} мин ${s % 60} с"
            }
        },
            icon = {
                Text("⏱", color = Color.White, fontSize = 14.sp)
            }
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Кнопка тестирования клика
        GlassButton(
            onClick = onTestClick,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("test_point_${point.id}_btn"),
            height = 46.dp,
            isOn = point.isConfigured
        ) {
            Text(
                text = if (point.isConfigured) "Тест клика (${point.x.toInt()}, ${point.y.toInt()})" else "Координаты не заданы",
                color = if (point.isConfigured) Color.White else TextMuted,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        var showResetConfirm by remember { mutableStateOf(false) }

        // Кнопка сброса точки
        GlassButton(
            onClick = { showResetConfirm = true },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("reset_point_${point.id}_btn"),
            height = 42.dp,
            hueOffset = -90f
        ) {
            Text(
                text = "Сбросить точку",
                color = AccentRed,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }

        if (showResetConfirm) {
            com.example.autoclicker.ui.theme.GlassDialog(
                title = "Вы уверены?",
                onDismissRequest = { showResetConfirm = false },
                onConfirm = {
                    showResetConfirm = false
                    onResetPoint()
                },
                confirmText = "Сбросить",
                cancelText = "Отмена"
            ) {
                Text(
                    text = "Сбросить координаты и настройки точки ${point.id}?",
                    color = Color.White,
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
fun PointConfigCard(
    point: ClickPoint,
    onTestClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onCountChange: (Int) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onResetPoint: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    GlassPanel(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .testTag("point_${point.id}_card")
    ) {
        PointConfigContent(
            point = point,
            onTestClick = onTestClick,
            onToggleEnabled = onToggleEnabled,
            onCountChange = onCountChange,
            onIntervalChange = onIntervalChange,
            onResetPoint = onResetPoint
        )
    }
}
