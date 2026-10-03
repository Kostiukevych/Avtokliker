package com.example.autoclicker.data

/**
 * Полный набор настроек автокликера.
 *
 * @param point1 Точка 1: кнопка Continue / результат матча.
 * @param point2 Точка 2: второе нажатие Continue.
 * @param point3 Точка 3: кнопка Start / Начать в лобби.
 * @param point4..point10 Точки 4..10: дополнительные настраиваемые точки (выключены по умолчанию).
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
    val point4: ClickPoint = ClickPoint(4, "Точка 4", enabled = false),
    val point5: ClickPoint = ClickPoint(5, "Точка 5", enabled = false),
    val point6: ClickPoint = ClickPoint(6, "Точка 6", enabled = false),
    val point7: ClickPoint = ClickPoint(7, "Точка 7", enabled = false),
    val point8: ClickPoint = ClickPoint(8, "Точка 8", enabled = false),
    val point9: ClickPoint = ClickPoint(9, "Точка 9", enabled = false),
    val point10: ClickPoint = ClickPoint(10, "Точка 10", enabled = false),
    val cycleDelayMinutes: Int = 7,
    val overlayX: Int = 100,
    val overlayY: Int = 200,
    val pointsOrientation: Int = 0,
    val pointsScreenWidth: Int = 0,
    val pointsScreenHeight: Int = 0,
    val isSmartMode: Boolean = false,
    val isDebugScreenshots: Boolean = false,
    val isSwipesEnabled: Boolean = false,
    val swipes: List<SwipeAction> = listOf(
        SwipeAction(id = 1),
        SwipeAction(id = 2),
        SwipeAction(id = 3)
    ),
    val recordedMacro: RecordedMacro? = null,
    val macroRepeatCount: Int = 1,
    val macroIntervalSec: Int = 0,
    val neonBrightness: Int = 85
) {
    val allPoints: List<ClickPoint>
        get() = listOf(point1, point2, point3, point4, point5, point6, point7, point8, point9, point10)

    fun getPointById(id: Int): ClickPoint {
        return when (id) {
            1 -> point1
            2 -> point2
            3 -> point3
            4 -> point4
            5 -> point5
            6 -> point6
            7 -> point7
            8 -> point8
            9 -> point9
            10 -> point10
            else -> throw IllegalArgumentException("Неизвестный pointId: $id")
        }
    }

    fun getSwipeById(id: Int): SwipeAction {
        return swipes.find { it.id == id } ?: SwipeAction(id = id)
    }
}
