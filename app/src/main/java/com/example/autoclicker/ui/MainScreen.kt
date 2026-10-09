package com.example.autoclicker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.engine.CycleStatus
import com.example.autoclicker.ui.screens.MainScreen as ScreensMainScreen

@Composable
fun MainScreen(
    settingsRepository: SettingsRepository,
    hasOverlayPermission: Boolean,
    hasAccessibilityPermission: Boolean,
    isAutomationRunning: Boolean,
    onRequestOverlayPermission: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onStartAutomation: () -> Unit,
    onStopAutomation: () -> Unit,
    onToggleOverlay: (Boolean) -> Unit,
    viewModel: MainViewModel = viewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val cycleStatus by viewModel.cycleStatus.collectAsState()
    val currentAction by viewModel.currentAction.collectAsState()
    val lastAction by viewModel.lastAction.collectAsState()
    val nextAction by viewModel.nextAction.collectAsState()
    val remainingSeconds by viewModel.remainingSeconds.collectAsState()
    val countdownText by viewModel.countdownText.collectAsState()
    val cycleNumber by viewModel.cycleNumber.collectAsState()
    val isMacroRunning by viewModel.isMacroRunning.collectAsState()

    ScreensMainScreen(
        settings = settings,
        logs = logs,
        cycleStatus = if (isAutomationRunning && cycleStatus == CycleStatus.STOPPED) CycleStatus.RUNNING else cycleStatus,
        currentAction = currentAction,
        lastAction = lastAction,
        nextAction = nextAction,
        remainingSeconds = remainingSeconds,
        countdownText = countdownText,
        cycleNumber = cycleNumber,
        isAccessibilityConnected = hasAccessibilityPermission,
        isOverlayGranted = hasOverlayPermission,
        onOpenAccessibilitySettings = onOpenAccessibilitySettings,
        onOpenOverlaySettings = onRequestOverlayPermission,
        onLaunchOverlayService = { onToggleOverlay(true) },
        onStartCycle = onStartAutomation,
        onStopCycle = onStopAutomation,
        onTestClick = { viewModel.testClick(it) },
        onUpdatePointConfig = { id, enabled, count, interval ->
            viewModel.updatePointConfig(id, enabled, count, interval)
        },
        onUpdateCycleDelay = { viewModel.updateCycleDelay(it) },
        onUpdateSmartMode = { viewModel.updateSmartMode(it) },
        onUpdateRunPoints = { viewModel.updateRunPoints(it) },
        onUpdateRunSwipes = { viewModel.updateRunSwipes(it) },
        onUpdateRunSmart = { viewModel.updateRunSmart(it) },
        onUpdateFirstCycleAllPoints = { viewModel.updateFirstCycleAllPoints(it) },
        onResetPoint = { viewModel.resetPoint(it) },
        onResetAllPoints = { viewModel.resetAllPoints() },
        onResetSwipe = { viewModel.resetSwipe(it) },
        onResetAllSwipes = { viewModel.resetAllSwipes() },
        onResetSmartMode = { viewModel.resetSmartMode() },
        onResetCycleDelay = { viewModel.resetCycleDelay() },
        onResetNeonBrightness = { viewModel.resetNeonBrightness() },
        onResetActions = { viewModel.resetActions() },
        onResetAll = { viewModel.resetAll() },
        onDeleteAllCustomConfigs = { viewModel.deleteAllCustomConfigs() },
        onUpdateDebugScreenshots = { viewModel.updateDebugScreenshots(it) },
        onUpdateSwipeConfig = { id, enabled, duration, interval ->
            viewModel.updateSwipeConfig(id, enabled, duration, interval)
        },
        onTestSwipe = { viewModel.testSwipe(it) },
        isMacroRunning = isMacroRunning,
        onStartMacro = { viewModel.startMacro() },
        onStopMacro = { viewModel.stopMacro() },
        onClearMacro = { viewModel.clearMacro() },
        onUpdateMacroConfig = { count, interval ->
            viewModel.updateMacroConfig(count, interval)
        },
        onClearLogs = { viewModel.clearLogs() },
        onCheckConfig = { onResult -> viewModel.checkConfigNow(onResult) }
    )
}
