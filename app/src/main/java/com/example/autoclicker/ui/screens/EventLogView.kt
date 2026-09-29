package com.example.autoclicker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.autoclicker.ui.theme.AccentAmber
import com.example.autoclicker.ui.theme.AccentGreen
import com.example.autoclicker.ui.theme.AccentRed
import com.example.autoclicker.ui.theme.PrimaryBlue
import com.example.autoclicker.ui.theme.SurfaceDark
import com.example.autoclicker.ui.theme.TextMuted
import com.example.autoclicker.ui.theme.TextPrimary

@Composable
fun EventLogView(
    logs: List<String>,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
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
                text = "ЛОГ СОБЫТИЙ (ПОСЛЕДНИЕ 30)",
                color = PrimaryBlue,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )

            TextButton(onClick = onClearLogs) {
                Text("Очистить", color = TextMuted, fontSize = 12.sp)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(SurfaceDark, RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFF2E3346), RoundedCornerShape(10.dp))
                .padding(8.dp)
        ) {
            if (logs.isEmpty()) {
                Text(
                    text = "События пока отсутствуют. Нажмите START или выполните калибровку точки.",
                    color = TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(logs) { logEntry ->
                        val textColor = when {
                            logEntry.contains("ERROR", ignoreCase = true) -> AccentRed
                            logEntry.contains("TAP", ignoreCase = true) -> AccentGreen
                            logEntry.contains("WAIT", ignoreCase = true) -> AccentAmber
                            logEntry.contains("STARTED", ignoreCase = true) -> PrimaryBlue
                            else -> TextPrimary
                        }

                        Text(
                            text = logEntry,
                            color = textColor,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
