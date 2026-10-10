package com.example.autoclicker.ui

import android.app.Application
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.autoclicker.data.ClickerSettings
import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.data.ResetManager
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.engine.CheckReport
import com.example.autoclicker.engine.CycleStatus
import com.example.autoclicker.engine.GestureExecutor
import com.example.autoclicker.engine.MacroController
import com.example.autoclicker.engine.RunCoordinator
import com.example.autoclicker.engine.SmartEngine
import com.example.autoclicker.service.AccessibilityServiceHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    private val smartEngine = SmartEngine.getInstance(application, gestureExecutor, settingsRepo)
    private val macroController = MacroController.getInstance(gestureExecutor, settingsRepo)

    fun checkConfigNow(onResult: (CheckReport) -> Unit) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                delay(5000L)
                val report = smartEngine.checkConfigNow()
                viewModelScope.launch(Dispatchers.Main) {
                    onResult(report)
                }
            } catch (t: Throwable) {
                val errReport = CheckReport(
                    message = "Ошибка проверки: ${t.message ?: t.toString()}",
                    lines = emptyList()
                )
                viewModelScope.launch(Dispatchers.Main) {
                    onResult(errReport)
                }
            }
        }
    }

    val settings: StateFlow<ClickerSettings> = settingsRepo.settings
    val logs: StateFlow<List<String>> = EventLogManager.logs
    val isMacroRunning: StateFlow<Boolean> = macroController.isRunning

    val cycleStatus: StateFlow<CycleStatus> = combine(
        smartEngine.status,
        macroController.isRunning
    ) { sStatus: CycleStatus, macroRunning: Boolean ->
        when {
            sStatus != CycleStatus.STOPPED -> sStatus
            macroRunning -> CycleStatus.RUNNING
            else -> CycleStatus.STOPPED
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CycleStatus.STOPPED)

    val currentAction: StateFlow<String> = combine(
        smartEngine.currentAction,
        smartEngine.status,
        macroController.isRunning
    ) { sAct: String, sStatus: CycleStatus, macroRunning: Boolean ->
        when {
            macroRunning -> "Воспроизведение макроса"
            sStatus != CycleStatus.STOPPED -> sAct
            else -> "Остановлен"
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Остановлен")

    val lastAction: StateFlow<String> = smartEngine.lastAction
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "—")

    val nextAction: StateFlow<String> = smartEngine.nextAction
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Готов к запуску")

    val remainingSeconds: StateFlow<Int> = smartEngine.remainingSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val countdownText: StateFlow<String> = smartEngine.countdownText
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val cycleNumber: StateFlow<Int> = smartEngine.cycleNumber
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

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

    fun updateSmartMode(enabled: Boolean) {
        settingsRepo.updateRunSmart(enabled)
    }

    fun updateRunSmart(enabled: Boolean) {
        settingsRepo.updateRunSmart(enabled)
    }

    fun updateDebugScreenshots(enabled: Boolean) {
        settingsRepo.updateDebugScreenshots(enabled)
    }

    fun startCycle() {
        RunCoordinator.start(getApplication())
    }

    fun stopCycle() {
        RunCoordinator.stopAll(getApplication(), "кнопка Стоп")
        macroController.stop()
    }

    fun resetSmartMode() {
        ResetManager.resetSmartMode(getApplication())
    }

    fun resetNeonBrightness() {
        ResetManager.resetNeonBrightness(getApplication())
    }

    fun resetActions() {
        ResetManager.resetActions(getApplication())
    }

    fun resetAll() {
        ResetManager.resetAll(getApplication())
    }

    fun deleteAllCustomConfigs() {
        ResetManager.deleteAllCustomConfigs(getApplication())
    }

    fun startMacro() {
        val currentSettings = settingsRepo.getLatestSettings()
        macroController.start(currentSettings.macroRepeatCount, currentSettings.macroIntervalSec)
    }

    fun stopMacro() {
        macroController.stop()
    }

    fun clearMacro() {
        ResetManager.deleteAllMacros(getApplication())
    }

    fun updateMacroConfig(repeatCount: Int, intervalSec: Int) {
        settingsRepo.updateMacroConfig(repeatCount, intervalSec)
    }

    fun clearLogs() {
        EventLogManager.clearAll()
    }
}
