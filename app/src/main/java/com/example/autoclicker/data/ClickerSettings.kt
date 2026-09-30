package com.example.autoclicker.data

/**
 * Полный набор настроек автокликера.
 *
 * @param point1 Точка 1: кнопка Continue / результат матча.
 * @param point2 Точка 2: второе нажатие Continue.
 * @param point3 Точка 3: кнопка Start / Начать в лобби.
 * @param cycleDelayMinutes Фиксированная задержка между циклами в минутах (1..15 минут).
 * @param overlayX Последняя сохраненная координата X плавающего окна.
 * @param overlayY Последняя сохраненная координата Y плавающего окна.
 * @param pointsOrientation Ориентация экрана при калибровке (1=Portrait, 2=Landscape).
 * @param pointsScreenWidth Ширина экрана в пикселях при калибровке.
 * @param pointsScreenHeight Высота экрана в пикселях при калибровке.
 */
data class ClickerSettings(
    val point1: ClickPoint = ClickPoint(1, "Результат матча / Continue"),
    val point2: ClickPoint = ClickPoint(2, "Второе Continue"),
    val point3: ClickPoint = ClickPoint(3, "Старт в лобби"),
    val cycleDelayMinutes: Int = 7,
    val overlayX: Int = 100,
    val overlayY: Int = 200,
    val pointsOrientation: Int = 0,
    val pointsScreenWidth: Int = 0,
    val pointsScreenHeight: Int = 0
) {
    fun getPointById(id: Int): ClickPoint {
        return when (id) {
            1 -> point1
            2 -> point2
            3 -> point3
            else -> throw IllegalArgumentException("Неизвестный pointId: $id")
        }
    }
}
