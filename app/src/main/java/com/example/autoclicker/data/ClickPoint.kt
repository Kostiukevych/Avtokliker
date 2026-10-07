package com.example.autoclicker.data

/**
 * Модель точки нажатия на экране.
 *
 * @param id Уникальный идентификатор (1, 2, 3)
 * @param name Название точки (например, "POINT 1: Continue", "POINT 2: Confirm", "POINT 3: Lobby Start")
 * @param x Координата X в пикселях
 * @param y Координата Y в пикселях
 * @param enabled Активна ли точка в цикле
 * @param clickCount Количество повторных нажатий (1..10)
 * @param intervalSec Интервал между повторными нажатиями этой точки в секундах (1..300)
 */
data class ClickPoint(
    val id: Int,
    val name: String,
    val x: Float = 0f,
    val y: Float = 0f,
    val enabled: Boolean = true,
    val clickCount: Int = 1,
    val intervalSec: Int = 2
) {
    val isConfigured: Boolean
        get() = x > 0f || y > 0f
}
