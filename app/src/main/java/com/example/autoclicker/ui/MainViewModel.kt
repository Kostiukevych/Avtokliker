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
import com.example.autoclicker.service.AccessibilityServiceHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepo = SettingsRepository.getInstance(application)
    private val gestureExecutor = GestureExecutor()
    private val cycleController = CycleController.getInstance(gestureExecutor, settingsRepo)

    val settings: StateFlow<ClickerSettings> = settingsRepo.settings
    val logs: StateFlow<List<String>> = EventLogManager.logs

    val cycleStatus: StateFlow<CycleStatus> = cycleController.status
    val currentAction: StateFlow<String> = cycleController.currentAction
    val lastAction: StateFlow<String> = cycleController.lastAction
    val nextAction: StateFlow<String> = cycleController.nextAction
    val remainingSeconds: StateFlow<Int> = cycleController.remainingSeconds
    val countdownText: StateFlow<String> = cycleController.countdownText
    val cycleNumber: StateFlow<Int> = cycleController.cycleNumber

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

    fun startCycle() {
        cycleController.start()
    }

    fun stopCycle() {
        cycleController.stop()
    }

    fun testClick(pointId: Int) {
        cycleController.testClick(pointId)
    }

    fun clearLogs() {
        EventLogManager.clear()
    }
}
