package com.example.autoclicker.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AccessibilityServiceHolder {
    private val _service = MutableStateFlow<AutoClickAccessibilityService?>(null)
    val service: StateFlow<AutoClickAccessibilityService?> = _service.asStateFlow()

    val isConnected: Boolean
        get() = _service.value != null

    fun setService(service: AutoClickAccessibilityService?) {
        _service.value = service
    }
}
