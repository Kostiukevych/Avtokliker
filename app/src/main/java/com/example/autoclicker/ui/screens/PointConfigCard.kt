package com.example.autoclicker.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.autoclicker.data.ClickPoint
import com.example.autoclicker.ui.theme.AccentGreen
import com.example.autoclicker.ui.theme.AccentRed
import com.example.autoclicker.ui.theme.PrimaryBlue
import com.example.autoclicker.ui.theme.SurfaceDark
import com.example.autoclicker.ui.theme.TextMuted
import com.example.autoclicker.ui.theme.TextPrimary
import com.example.autoclicker.ui.theme.TextSecondary

@Composable
fun PointConfigCard(
    point: ClickPoint,
    onRecordClick: () -> Unit,
    onTestClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onCountChange: (Int) -> Unit,
    onIntervalChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .testTag("point_${point.id}_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, Color(0xFF2E3346))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Заголовок карточки и свитч включения
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "POINT ${point.id}",
                        color = PrimaryBlue,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = point.name,
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }

                Switch(
                    checked = point.enabled,
                    onCheckedChange = onToggleEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = AccentGreen,
                        checkedTrackColor = Color(0xFF1B5E20)
                    )
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Отображение записанных координат
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF141720), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text(
                    text = "Recorded:",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                if (point.isConfigured) {
                    Text(
                        text = "X = ${point.x.toInt()}\nY = ${point.y.toInt()}",
                        color = Color(0xFFFFAB00),
                        fontWeight = FontWeight.Bold,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 14.sp
                    )
                } else {
                    Text(
                        text = "X = —\nY = — (не заданы)",
                        color = AccentRed,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Настройка количества нажатий (1..10)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Количество нажатий:",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Text(
                    text = "${point.clickCount} раз",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
            Slider(
                value = point.clickCount.toFloat(),
                onValueChange = { onCountChange(it.toInt()) },
                valueRange = 1f..10f,
                steps = 8
            )

            // Настройка интервала повторных нажатий (1..15 сек)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Интервал между нажатиями:",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                Text(
                    text = "${point.intervalSec} сек",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
            Slider(
                value = point.intervalSec.toFloat(),
                onValueChange = { onIntervalChange(it.toInt()) },
                valueRange = 1f..15f,
                steps = 13
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Кнопки калибровки и тестирования
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onRecordClick,
                    modifier = Modifier
                        .weight(1.2f)
                        .testTag("record_point_${point.id}_btn"),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Record Point ${point.id}", fontSize = 12.sp, maxLines = 1)
                }

                Button(
                    onClick = onTestClick,
                    enabled = point.isConfigured,
                    modifier = Modifier
                        .weight(0.8f)
                        .testTag("test_point_${point.id}_btn"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentGreen,
                        disabledContainerColor = Color(0xFF26382E)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("TEST", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}
