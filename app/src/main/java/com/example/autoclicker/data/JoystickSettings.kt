package com.example.autoclicker.data

data class StickConfig(
    val id: Int,
    val enabled: Boolean = false,
    val x: Float = 0f,
    val y: Float = 0f,
    val radiusPx: Int = 120,
    val mode: String = "hold",
    val angleDeg: Int = 0,
    val strength: Int = 100,
    val circlePeriodSec: Int = 4
) {
    val isConfigured get() = x > 0f || y > 0f
}

data class FireButtonConfig(
    val enabled: Boolean = false,
    val x: Float = 0f,
    val y: Float = 0f,
    val mode: String = "tap",
    val intervalMs: Int = 400
) {
    val isConfigured get() = x > 0f || y > 0f
}

data class JoystickSettings(
    val masterEnabled: Boolean = false,
    /** Автомимикрия: сценарий движения на всю катку до «Продолжить», затем повтор. */
    val autoMimicEnabled: Boolean = false,
    val stick1: StickConfig = StickConfig(1),
    val stick2: StickConfig = StickConfig(2),
    val button: FireButtonConfig = FireButtonConfig(),
    val screenW: Int = 0,
    val screenH: Int = 0
) {
    val hasAnyActive get() = (stick1.enabled && stick1.isConfigured) ||
        (stick2.enabled && stick2.isConfigured) ||
        (button.enabled && button.isConfigured)
}
