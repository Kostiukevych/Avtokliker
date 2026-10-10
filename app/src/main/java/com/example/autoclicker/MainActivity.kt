package com.example.autoclicker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.autoclicker.ui.MainViewModel
import com.example.autoclicker.ui.screens.MainScreen
import com.example.autoclicker.ui.theme.AutoClickerTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutoClickerTheme {
                val settings by viewModel.settings.collectAsState()
                val accConnected by viewModel.isAccessibilityConnected.collectAsState()
                val accEnabled by viewModel.isAccessibilityEnabledInSystem.collectAsState()
                val overlayGranted by viewModel.isOverlayGranted.collectAsState()

                MainScreen(
                    settings = settings,
                    isAccessibilityConnected = accConnected,
                    isOverlayGranted = overlayGranted,
                    isAccessibilityEnabledInSystem = accEnabled,
                    onOpenAccessibilitySettings = { viewModel.openAccessibilitySettings() },
                    onOpenOverlaySettings = { viewModel.openOverlaySettings() },
                    onLaunchOverlayService = { viewModel.launchOverlayService() }
                )
            }
        }
    }
}
