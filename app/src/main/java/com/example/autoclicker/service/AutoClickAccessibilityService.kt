package com.example.autoclicker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.example.autoclicker.data.EventLogManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Сервис специальных возможностей (AccessibilityService).
 *
 * Отвечает ТОЛЬКО за выполнение жестов (dispatchGesture) через GestureExecutor.
 * НЕ запускает бесконечных циклов автоматизации самостоятельно.
 */
class AutoClickAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        val caps = serviceInfo?.capabilities ?: 0
        val canPerform = (caps and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0
        EventLogManager.log(
            EventLogManager.TAG_ACCESSIBILITY,
            "AccessibilityService подключен (canPerformGestures=$canPerform)"
        )
        AccessibilityServiceHolder.setService(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // События интерфейса не требуются для кликера по координатам
    }

    override fun onInterrupt() {
        EventLogManager.log(
            EventLogManager.TAG_ACCESSIBILITY,
            "AccessibilityService прерван системой"
        )
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AccessibilityServiceHolder.setService(null)
        EventLogManager.log(
            EventLogManager.TAG_ACCESSIBILITY,
            "AccessibilityService отключен"
        )
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AccessibilityServiceHolder.setService(null)
        super.onDestroy()
    }
}

/**
 * Потокобезопасный держатель активного экземпляра AccessibilityService.
 */
object AccessibilityServiceHolder {
    private val _service = MutableStateFlow<AutoClickAccessibilityService?>(null)
    val service: StateFlow<AutoClickAccessibilityService?> = _service.asStateFlow()

    val isConnected: Boolean
        get() = _service.value != null

    fun setService(instance: AutoClickAccessibilityService?) {
        _service.value = instance
    }
}
