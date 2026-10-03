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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.example.autoclicker.ui.theme.AccentAmber
import com.example.autoclicker.ui.theme.AccentGreen
import com.example.autoclicker.ui.theme.AccentRed
import com.example.autoclicker.ui.theme.GlassPanel
import com.example.autoclicker.ui.theme.PrimaryBlue
import com.example.autoclicker.ui.theme.TextMuted
import com.example.autoclicker.ui.theme.TextPrimary
import com.example.autoclicker.ui.theme.TextSecondary

@Composable
fun EventLogView(
    logs: List<String>,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassPanel(
        modifier = modifier
            .fillMaxWidth()
            .testTag("event_log_container")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "ЖУРНАЛ СОБЫТИЙ (ПОСЛЕДНИЕ 30)",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 0.5.sp
            )

            TextButton(onClick = onClearLogs) {
                Text("Очистить", color = TextSecondary, fontSize = 11.sp)
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF0F101A).copy(alpha = 0.90f), Color(0xFF06070B).copy(alpha = 0.98f))
                    )
                )
                .padding(8.dp)
        ) {
            if (logs.isEmpty()) {
                Text(
                    text = "События пока отсутствуют. Нажмите START или выполните действие.",
                    color = TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(logs) { logEntry ->
                        val textColor = when {
                            logEntry.contains("ERROR", ignoreCase = true) || logEntry.contains("ОШИБКА", ignoreCase = true) -> AccentRed
                            logEntry.contains("TAP", ignoreCase = true) || logEntry.contains("НАЖАТИЕ", ignoreCase = true) -> AccentGreen
                            logEntry.contains("WAIT", ignoreCase = true) || logEntry.contains("ОЖИДАНИЕ", ignoreCase = true) -> AccentAmber
                            logEntry.contains("START", ignoreCase = true) || logEntry.contains("ЗАПУСК", ignoreCase = true) -> Color(0xFF80D8FF)
                            else -> TextPrimary
                        }

                        Text(
                            text = logEntry,
                            color = textColor,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 1.5.dp)
                        )
                    }
                }
            }
        }
    }
}
