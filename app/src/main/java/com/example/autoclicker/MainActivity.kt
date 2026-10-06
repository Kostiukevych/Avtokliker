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
                val isMacroRunning by viewModel.isMacroRunning.collectAsState()

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
                    onTestClick = { pointId -> viewModel.testClick(pointId) },
                    onUpdatePointConfig = { id, enabled, count, interval ->
                        viewModel.updatePointConfig(id, enabled, count, interval)
                    },
                    onUpdateCycleDelay = { minutes ->
                        viewModel.updateCycleDelay(minutes)
                    },
                    onUpdateSmartMode = { enabled ->
                        viewModel.updateSmartMode(enabled)
                    },
                    onUpdateRunPoints = { enabled ->
                        viewModel.updateRunPoints(enabled)
                    },
                    onUpdateRunSwipes = { enabled ->
                        viewModel.updateRunSwipes(enabled)
                    },
                    onUpdateRunSmart = { enabled ->
                        viewModel.updateRunSmart(enabled)
                    },
                    onUpdateFirstCycleAllPoints = { enabled ->
                        viewModel.updateFirstCycleAllPoints(enabled)
                    },
                    onResetPoint = { id ->
                        viewModel.resetPoint(id)
                    },
                    onResetAllPoints = {
                        viewModel.resetAllPoints()
                    },
                    onResetSwipe = { id ->
                        viewModel.resetSwipe(id)
                    },
                    onResetAllSwipes = {
                        viewModel.resetAllSwipes()
                    },
                    onDeleteAllMacros = {
                        viewModel.clearMacro()
                    },
                    onResetSmartMode = {
                        viewModel.resetSmartMode()
                    },
                    onResetCycleDelay = {
                        viewModel.resetCycleDelay()
                    },
                    onResetNeonBrightness = {
                        viewModel.resetNeonBrightness()
                    },
                    onResetActions = {
                        viewModel.resetActions()
                    },
                    onResetAll = {
                        viewModel.resetAll()
                    },
                    onDeleteAllCustomConfigs = {
                        viewModel.deleteAllCustomConfigs()
                    },
                    onUpdateDebugScreenshots = { enabled ->
                        viewModel.updateDebugScreenshots(enabled)
                    },
                    onUpdateSwipeConfig = { id, enabled, duration, interval ->
                        viewModel.updateSwipeConfig(id, enabled, duration, interval)
                    },
                    onTestSwipe = { id ->
                        viewModel.testSwipe(id)
                    },
                    isMacroRunning = isMacroRunning,
                    onStartMacro = { viewModel.startMacro() },
                    onStopMacro = { viewModel.stopMacro() },
                    onStartMacroRecording = { launchMacroRecording() },
                    onClearMacro = { viewModel.clearMacro() },
                    onUpdateMacroConfig = { repeatCount, intervalSec ->
                        viewModel.updateMacroConfig(repeatCount, intervalSec)
                    },
                    onClearLogs = { viewModel.clearLogs() },
                    onCheckConfig = { onResult -> viewModel.checkConfigNow(onResult) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkPermissions()
    }

    /**
     * Автоматический запуск плавающей кнопки при сворачивании приложения (Requirement А).
     */
    override fun onStop() {
        super.onStop()
        if (Settings.canDrawOverlays(this)) {
            try {
                FloatingOverlayService.start(this)
                AutoClickForegroundService.start(this)
            } catch (e: Throwable) {
                com.example.autoclicker.data.EventLogManager.log(
                    com.example.autoclicker.data.EventLogManager.TAG_AUTO_CLICKER,
                    "ERROR: onStop service start: ${e.message}",
                    isError = true
                )
            }
        }
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
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    private fun launchOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings()
            return
        }
        try {
            FloatingOverlayService.start(this)
            AutoClickForegroundService.start(this)
            Toast.makeText(this, "Плавающая кнопка активирована", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            com.example.autoclicker.data.EventLogManager.log(
                com.example.autoclicker.data.EventLogManager.TAG_AUTO_CLICKER,
                "ERROR: Не удалось запустить сервис: ${e.message}",
                isError = true
            )
        }
    }

    private fun launchMacroRecording() {
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings()
            return
        }
        val overlay = com.example.autoclicker.service.MacroRecordingOverlayView(
            context = this,
            onMacroRecorded = {},
            onDismissed = {}
        )
        overlay.show()
    }
}
