package com.example.autoclicker.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.autoclicker.data.ClickerSettings
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.ui.components.PermissionButtonsWebView
import com.example.autoclicker.ui.theme.LocalNeonHue
import com.example.autoclicker.ui.theme.NeonTheme
import com.example.autoclicker.ui.theme.ProvideNeon
import com.example.autoclicker.ui.theme.liquidGlassBackground

/**
 * Главный экран (шаг 3): только разрешения + кнопка вызова плавающей кнопки.
 */
@Composable
fun MainScreen(
    settings: ClickerSettings,
    isAccessibilityConnected: Boolean,
    isOverlayGranted: Boolean,
    isAccessibilityEnabledInSystem: Boolean = false,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onLaunchOverlayService: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    remember { NeonTheme.init(context); true }
    val neonBriFraction = settings.neonBrightness / 100f

    ProvideNeon(brightness = neonBriFraction) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .liquidGlassBackground()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Автокликер",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF1F4FB)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Умный режим",
                    fontSize = 14.sp,
                    color = Color(0xFF80D8FF)
                )
                Spacer(Modifier.height(28.dp))

                // Accessibility + Overlay (исчезают после выдачи)
                if (!isOverlayGranted || !isAccessibilityEnabledInSystem) {
                    PermissionButtonsWebView(
                        isOverlayGranted = isOverlayGranted,
                        isAccessibilityGranted = isAccessibilityEnabledInSystem,
                        showLaunchButton = false,
                        onRequestOverlay = onOpenOverlaySettings,
                        onRequestAccessibility = onOpenAccessibilitySettings,
                        onLaunchOverlay = {},
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (isAccessibilityEnabledInSystem && !isAccessibilityConnected) {
                        Text(
                            text = "Accessibility включён, подключаюсь…",
                            color = Color(0xFF80D8FF),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }

                // Постоянная кнопка «Вызов плавающей кнопки»
                PermissionButtonsWebView(
                    isOverlayGranted = true,
                    isAccessibilityGranted = true,
                    showLaunchButton = true,
                    launchOnly = true,
                    onRequestOverlay = {},
                    onRequestAccessibility = {},
                    onLaunchOverlay = {
                        if (!isAccessibilityEnabledInSystem || !isOverlayGranted) {
                            val missing = buildList {
                                if (!isAccessibilityEnabledInSystem) add("Accessibility")
                                if (!isOverlayGranted) add("Overlay")
                            }.joinToString(", ")
                            Toast.makeText(
                                context,
                                "Нужны разрешения: $missing",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            SettingsRepository.getInstance(context).setOverlayWanted(true)
                            onLaunchOverlayService()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
