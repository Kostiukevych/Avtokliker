package com.example.autoclicker.data

/**
 * Набор настроек автокликера.
 *
 * @param overlayX Последняя сохраненная координата X плавающего окна.
 * @param overlayY Последняя сохраненная координата Y плавающего окна.
 * @param isSmartMode Включен ли Умный режим.
 * @param isDebugScreenshots Сохранять ли отладочные снимки экрана.
 */
data class ClickerSettings(
    val overlayX: Int = 100,
    val overlayY: Int = 200,
    val isSmartMode: Boolean = true,
    val isDebugScreenshots: Boolean = false,
    val recordedMacro: RecordedMacro? = null,
    val macroRepeatCount: Int = 1,
    val macroIntervalSec: Int = 0,
    val neonBrightness: Int = 85,
    /** Отложенный старт: если true и scheduleAtEpochMs > now — ждём */
    val scheduleEnabled: Boolean = false,
    /** Unix ms момента первого запуска (0 = не задано) */
    val scheduleAtEpochMs: Long = 0L,
    /** "all" | "macro" | "smart" */
    val scheduleTarget: String = "all"
)
