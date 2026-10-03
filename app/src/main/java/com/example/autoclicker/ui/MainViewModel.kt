package com.example.autoclicker.ui

import android.app.Application
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.autoclicker.data.ClickerSettings
import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.engine.CycleController
import com.example.autoclicker.engine.CycleStatus
import com.example.autoclicker.engine.GestureExecutor
import com.example.autoclicker.engine.SmartEngine
import com.example.autoclicker.engine.SwipeController
import com.example.autoclicker.service.AccessibilityServiceHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepo = SettingsRepository.getInstance(application)
    private val gestureExecutor = GestureExecutor()
    private val cycleController = CycleController.getInstance(gestureExecutor, settingsRepo)
    private val smartEngine = SmartEngine.getInstance(application, gestureExecutor, settingsRepo)
    private val swipeController = SwipeController.getInstance(gestureExecutor, settingsRepo)
    private val macroController = com.example.autoclicker.engine.MacroController.getInstance(gestureExecutor, settingsRepo)

    val settings: StateFlow<ClickerSettings> = settingsRepo.settings
    val logs: StateFlow<List<String>> = EventLogManager.logs
    val isMacroRunning: StateFlow<Boolean> = macroController.isRunning

    val cycleStatus: StateFlow<CycleStatus> = combine(
        cycleController.status,
        smartEngine.status,
        swipeController.isRunning,
        macroController.isRunning
    ) { cStatus: CycleStatus, sStatus: CycleStatus, swipeRunning: Boolean, macroRunning: Boolean ->
        when {
            sStatus != CycleStatus.STOPPED -> sStatus
            cStatus != CycleStatus.STOPPED -> cStatus
            swipeRunning || macroRunning -> CycleStatus.RUNNING
            else -> CycleStatus.STOPPED
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CycleStatus.STOPPED)

    val currentAction: StateFlow<String> = combine(
        cycleController.currentAction,
        smartEngine.currentAction,
        smartEngine.status,
        swipeController.isRunning,
        macroController.isRunning
    ) { cAct: String, sAct: String, sStatus: CycleStatus, swipeRunning: Boolean, macroRunning: Boolean ->
        when {
            macroRunning -> "Воспроизведение макроса"
            sStatus != CycleStatus.STOPPED -> sAct
            cAct != "Остановлен" -> cAct
            swipeRunning -> "Свайпы активны"
            else -> "Остановлен"
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Остановлен")

    val lastAction: StateFlow<String> = combine(
        cycleController.lastAction,
        smartEngine.lastAction,
        smartEngine.status
    ) { cAct: String, sAct: String, sStatus: CycleStatus ->
        if (sStatus != CycleStatus.STOPPED) sAct else cAct
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "—")

    val nextAction: StateFlow<String> = combine(
        cycleController.nextAction,
        smartEngine.nextAction,
        smartEngine.status
    ) { cAct: String, sAct: String, sStatus: CycleStatus ->
        if (sStatus != CycleStatus.STOPPED) sAct else cAct
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Готов к запуску")

    val remainingSeconds: StateFlow<Int> = combine(
        cycleController.remainingSeconds,
        smartEngine.remainingSeconds,
        smartEngine.status
    ) { cSec: Int, sSec: Int, sStatus: CycleStatus ->
        if (sStatus != CycleStatus.STOPPED) sSec else cSec
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val countdownText: StateFlow<String> = combine(
        cycleController.countdownText,
        smartEngine.countdownText,
        smartEngine.status
    ) { cCd: String, sCd: String, sStatus: CycleStatus ->
        if (sStatus != CycleStatus.STOPPED) sCd else cCd
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val cycleNumber: StateFlow<Int> = combine(
        cycleController.cycleNumber,
        smartEngine.cycleNumber,
        smartEngine.status
    ) { cNum: Int, sNum: Int, sStatus: CycleStatus ->
        if (sStatus != CycleStatus.STOPPED) sNum else cNum
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val isAccessibilityConnected: StateFlow<Boolean> = AccessibilityServiceHolder.service
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AccessibilityServiceHolder.service.value != null
        ).let { flow ->
            val mapped = MutableStateFlow(AccessibilityServiceHolder.isConnected)
            viewModelScope.launch {
                flow.collect { mapped.value = it != null }
            }
            mapped.asStateFlow()
        }

    private val _isOverlayPermissionGranted = MutableStateFlow(checkOverlayPermission())
    val isOverlayPermissionGranted: StateFlow<Boolean> = _isOverlayPermissionGranted.asStateFlow()

    fun checkPermissions() {
        _isOverlayPermissionGranted.value = checkOverlayPermission()
    }

    private fun checkOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(getApplication())
    }

    fun updatePointConfig(id: Int, enabled: Boolean, count: Int, interval: Int) {
        settingsRepo.updatePointConfig(id, enabled, count, interval)
    }

    fun updateCycleDelay(minutes: Int) {
        settingsRepo.updateCycleDelay(minutes)
    }

    fun updateSmartMode(enabled: Boolean) {
        settingsRepo.updateSmartMode(enabled)
    }

    fun updateDebugScreenshots(enabled: Boolean) {
        settingsRepo.updateDebugScreenshots(enabled)
    }

    fun startCycle() {
        val currentSettings = settingsRepo.getLatestSettings()
        if (currentSettings.isSwipesEnabled) {
            swipeController.start()
        }
        if (currentSettings.isSmartMode) {
            smartEngine.start()
        } else {
            cycleController.start()
        }
    }

    fun stopCycle() {
        macroController.stop()
        swipeController.stop()
        smartEngine.stop()
        cycleController.stop()
    }

    fun startMacro() {
        val currentSettings = settingsRepo.getLatestSettings()
        macroController.start(currentSettings.macroRepeatCount, currentSettings.macroIntervalSec)
    }

    fun stopMacro() {
        macroController.stop()
    }

    fun clearMacro() {
        settingsRepo.clearMacro()
    }

    fun updateMacroConfig(repeatCount: Int, intervalSec: Int) {
        settingsRepo.updateMacroConfig(repeatCount, intervalSec)
    }

    fun updateSwipesMasterEnabled(enabled: Boolean) {
        settingsRepo.updateSwipesMasterEnabled(enabled)
    }

    fun updateSwipeConfig(id: Int, enabled: Boolean, durationMs: Long, intervalSec: Int) {
        settingsRepo.updateSwipeConfig(id, enabled, durationMs, intervalSec)
    }

    fun testSwipe(swipeId: Int) {
        swipeController.testSwipe(swipeId)
    }

    fun testClick(pointId: Int) {
        cycleController.testClick(pointId)
    }

    fun clearLogs() {
        EventLogManager.clear()
    }
}
