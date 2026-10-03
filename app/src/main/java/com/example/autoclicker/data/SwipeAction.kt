package com.example.autoclicker.data

/**
 * Модель действия свайпа по экрану.
 *
 * @param id Уникальный идентификатор свайпа (1..3)
 * @param startX Начальная координата X в пикселях
 * @param startY Начальная координата Y в пикселях
 * @param endX Конечная координата X в пикселях
 * @param endY Конечная координата Y в пикселях
 * @param durationMs Длительность движения свайпа в миллисекундах (100..1500, по умолчанию 300)
 * @param intervalSec Интервал между повторениями свайпа в секундах (1..600, по умолчанию 10)
 * @param enabled Включен ли этот свайп
 */
data class SwipeAction(
    val id: Int,
    val startX: Float = 0f,
    val startY: Float = 0f,
    val endX: Float = 0f,
    val endY: Float = 0f,
    val durationMs: Long = 300L,
    val intervalSec: Int = 10,
    val enabled: Boolean = false
) {
    val isConfigured: Boolean
        get() = (startX > 0f || startY > 0f) && (endX > 0f || endY > 0f)
}
