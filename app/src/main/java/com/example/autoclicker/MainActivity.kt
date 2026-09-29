package com.example.autoclicker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.example.autoclicker.service.AutoClickForegroundService
import com.example.autoclicker.service.CalibrationOverlayView
import com.example.autoclicker.service.FloatingOverlayService
import com.example.autoclicker.ui.MainViewModel
import com.example.autoclicker.ui.screens.MainScreen
import com.example.autoclicker.ui.theme.AutoClickerTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted) {
                Toast.makeText(
                    this,
                    "Уведомления необходимы для надежной работы сервиса в фоне",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestNotificationPermissionIfNeeded()

        setContent {
            AutoClickerTheme {
                val settings by viewModel.settings.collectAsState()
                val logs by viewModel.logs.collectAsState()
                val cycleStatus by viewModel.cycleStatus.collectAsState()
                val currentAction by viewModel.currentAction.collectAsState()
                val lastAction by viewModel.lastAction.collectAsState()
                val nextAction by viewModel.nextAction.collectAsState()
                val remainingSeconds by viewModel.remainingSeconds.collectAsState()
                val countdownText by viewModel.countdownText.collectAsState()
                val cycleNumber by viewModel.cycleNumber.collectAsState()
                val isAccessibilityConnected by viewModel.isAccessibilityConnected.collectAsState()
                val isOverlayGranted by viewModel.isOverlayPermissionGranted.collectAsState()

                MainScreen(
                    settings = settings,
                    logs = logs,
                    cycleStatus = cycleStatus,
                    currentAction = currentAction,
                    lastAction = lastAction,
                    nextAction = nextAction,
                    remainingSeconds = remainingSeconds,
                    countdownText = countdownText,
                    cycleNumber = cycleNumber,
                    isAccessibilityConnected = isAccessibilityConnected,
                    isOverlayGranted = isOverlayGranted,
                    onOpenAccessibilitySettings = { openAccessibilitySettings() },
                    onOpenOverlaySettings = { openOverlaySettings() },
                    onLaunchOverlayService = { launchOverlay() },
                    onStartCycle = { viewModel.startCycle() },
                    onStopCycle = { viewModel.stopCycle() },
                    onRecordPoint = { pointId -> startCalibration(pointId) },
                    onTestClick = { pointId -> viewModel.testClick(pointId) },
                    onUpdatePointConfig = { id, enabled, count, interval ->
                        viewModel.updatePointConfig(id, enabled, count, interval)
                    },
                    onUpdateCycleDelay = { minutes ->
                        viewModel.updateCycleDelay(minutes)
                    },
                    onClearLogs = { viewModel.clearLogs() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkPermissions()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val isGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!isGranted) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        Toast.makeText(
            this,
            "Найдите AutoClicker в списке и включите его",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        }
    }

    private fun launchOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings()
            return
        }
        FloatingOverlayService.start(this)
        AutoClickForegroundService.start(this)
        Toast.makeText(this, "Плавающее окно активировано", Toast.LENGTH_SHORT).show()
    }

    private fun startCalibration(pointId: Int) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Для записи координат требуется разрешение на отображение поверх других окон",
                Toast.LENGTH_LONG
            ).show()
            openOverlaySettings()
            return
        }

        val overlay = CalibrationOverlayView(
            context = this,
            pointId = pointId,
            onCoordinateCaptured = { x, y ->
                Toast.makeText(
                    this,
                    "Point $pointId сохранена: X=${x.toInt()}, Y=${y.toInt()}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
        overlay.show()
    }
}
