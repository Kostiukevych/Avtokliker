package com.example.autoclicker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.autoclicker.data.SwipeAction
import com.example.autoclicker.ui.theme.AccentRed
import com.example.autoclicker.ui.theme.GlassButton
import com.example.autoclicker.ui.theme.GlassPanel
import com.example.autoclicker.ui.theme.GlassSlider
import com.example.autoclicker.ui.theme.GlassSwitch
import com.example.autoclicker.ui.theme.TextMuted
import com.example.autoclicker.ui.theme.TextSecondary

@Composable
fun SingleSwipeContent(
    swipe: SwipeAction,
    onToggleEnabled: (Boolean) -> Unit,
    onDurationChange: (Long) -> Unit,
    onIntervalChange: (Int) -> Unit,
    onTestSwipe: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        GlassSwitch(
            text = "Свайп ${swipe.id} включён",
            checked = swipe.enabled,
            onCheckedChange = onToggleEnabled,
            modifier = Modifier.testTag("swipe_${swipe.id}_switch")
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Координаты начала и конца
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
                    text = "ЛИНИЯ СВАЙПА:",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (swipe.isConfigured) {
                    Text(
                        text = "(${swipe.startX.toInt()}, ${swipe.startY.toInt()}) → (${swipe.endX.toInt()}, ${swipe.endY.toInt()})",
                        color = Color(0xFF8CFF00),
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                } else {
                    Text(
                        text = "Не задана (используйте плавающее окно)",
                        color = AccentRed,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Длительность свайпа
        Text(
            text = "ДЛИТЕЛЬНОСТЬ ЖЕСТА",
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )
        GlassSlider(
            value = swipe.durationMs.toFloat(),
            onValueChange = { onDurationChange(it.toLong()) },
            valueRange = 100f..2000f,
            steps = 18,
            formattedValue = "${swipe.durationMs} мс",
            icon = {
                Text("⚡", color = Color.White, fontSize = 14.sp)
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Интервал между свайпами
        Text(
            text = "ИНТЕРВАЛ МЕЖДУ СВАЙПАМИ",
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )
        GlassSlider(
            value = swipe.intervalSec.toFloat(),
            onValueChange = { onIntervalChange(it.toInt()) },
            valueRange = 1f..30f,
            steps = 28,
            formattedValue = "${swipe.intervalSec} с",
            icon = {
                Text("⏱", color = Color.White, fontSize = 14.sp)
            }
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Кнопка тестирования свайпа
        GlassButton(
            onClick = onTestSwipe,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("test_swipe_${swipe.id}_btn"),
            height = 46.dp,
            isOn = swipe.isConfigured
        ) {
            Text(
                text = if (swipe.isConfigured) "Тест свайпа #${swipe.id}" else "Линия свайпа не задана",
                color = if (swipe.isConfigured) Color.White else TextMuted,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
fun SwipesConfigCard(
    isSwipesEnabled: Boolean,
    swipes: List<SwipeAction>,
    onToggleMaster: (Boolean) -> Unit,
    onUpdateSwipeConfig: (Int, Boolean, Long, Int) -> Unit,
    onTestSwipe: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    GlassPanel(
        modifier = modifier
            .fillMaxWidth()
            .testTag("swipes_master_card")
    ) {
        GlassSwitch(
            text = "Свайпы включены",
            checked = isSwipesEnabled,
            onCheckedChange = onToggleMaster,
            modifier = Modifier.testTag("swipes_master_switch")
        )

        Spacer(modifier = Modifier.height(12.dp))

        swipes.forEach { swipe ->
            SingleSwipeContent(
                swipe = swipe,
                onToggleEnabled = { enabled ->
                    onUpdateSwipeConfig(swipe.id, enabled, swipe.durationMs, swipe.intervalSec)
                },
                onDurationChange = { dur ->
                    onUpdateSwipeConfig(swipe.id, swipe.enabled, dur, swipe.intervalSec)
                },
                onIntervalChange = { intv ->
                    onUpdateSwipeConfig(swipe.id, swipe.enabled, swipe.durationMs, intv)
                },
                onTestSwipe = { onTestSwipe(swipe.id) }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
