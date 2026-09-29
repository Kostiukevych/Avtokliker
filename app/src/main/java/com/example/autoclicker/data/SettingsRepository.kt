package com.example.autoclicker.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Репозиторий хранения настроек в SharedPreferences.
 * Сохраняет все координаты, таймеры и параметры точек на диск.
 */
class SettingsRepository private constructor(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<ClickerSettings> = _settings.asStateFlow()

    private fun loadSettings(): ClickerSettings {
        val p1 = ClickPoint(
            id = 1,
            name = "Результат матча / Continue",
            x = prefs.getFloat(KEY_P1_X, 0f),
            y = prefs.getFloat(KEY_P1_Y, 0f),
            enabled = prefs.getBoolean(KEY_P1_ENABLED, true),
            clickCount = prefs.getInt(KEY_P1_COUNT, 2),
            intervalSec = prefs.getInt(KEY_P1_INTERVAL, 2)
        )

        val p2 = ClickPoint(
            id = 2,
            name = "Второе Continue",
            x = prefs.getFloat(KEY_P2_X, 0f),
            y = prefs.getFloat(KEY_P2_Y, 0f),
            enabled = prefs.getBoolean(KEY_P2_ENABLED, true),
            clickCount = prefs.getInt(KEY_P2_COUNT, 2),
            intervalSec = prefs.getInt(KEY_P2_INTERVAL, 3)
        )

        val p3 = ClickPoint(
            id = 3,
            name = "Старт в лобби",
            x = prefs.getFloat(KEY_P3_X, 0f),
            y = prefs.getFloat(KEY_P3_Y, 0f),
            enabled = prefs.getBoolean(KEY_P3_ENABLED, true),
            clickCount = prefs.getInt(KEY_P3_COUNT, 1),
            intervalSec = prefs.getInt(KEY_P3_INTERVAL, 2)
        )

        val cycleDelayMinutes = prefs.getInt(KEY_CYCLE_DELAY_MINUTES, 7)
        val overlayX = prefs.getInt(KEY_OVERLAY_X, 50)
        val overlayY = prefs.getInt(KEY_OVERLAY_Y, 150)

        return ClickerSettings(
            point1 = p1,
            point2 = p2,
            point3 = p3,
            cycleDelayMinutes = cycleDelayMinutes,
            overlayX = overlayX,
            overlayY = overlayY
        )
    }

    fun updatePointCoordinates(pointId: Int, x: Float, y: Float) {
        val editor = prefs.edit()
        when (pointId) {
            1 -> {
                editor.putFloat(KEY_P1_X, x)
                editor.putFloat(KEY_P1_Y, y)
            }
            2 -> {
                editor.putFloat(KEY_P2_X, x)
                editor.putFloat(KEY_P2_Y, y)
            }
            3 -> {
                editor.putFloat(KEY_P3_X, x)
                editor.putFloat(KEY_P3_Y, y)
            }
        }
        editor.apply()
        _settings.value = loadSettings()
    }

    fun updatePoint(pointId: Int, x: Float, y: Float) = updatePointCoordinates(pointId, x, y)

    fun updatePointConfig(pointId: Int, enabled: Boolean, count: Int, interval: Int) {
        val editor = prefs.edit()
        when (pointId) {
            1 -> {
                editor.putBoolean(KEY_P1_ENABLED, enabled)
                editor.putInt(KEY_P1_COUNT, count)
                editor.putInt(KEY_P1_INTERVAL, interval)
            }
            2 -> {
                editor.putBoolean(KEY_P2_ENABLED, enabled)
                editor.putInt(KEY_P2_COUNT, count)
                editor.putInt(KEY_P2_INTERVAL, interval)
            }
            3 -> {
                editor.putBoolean(KEY_P3_ENABLED, enabled)
                editor.putInt(KEY_P3_COUNT, count)
                editor.putInt(KEY_P3_INTERVAL, interval)
            }
        }
        editor.apply()
        _settings.value = loadSettings()
    }

    fun updateCycleDelay(minutes: Int) {
        val sanitized = minutes.coerceIn(1, 15)
        prefs.edit().putInt(KEY_CYCLE_DELAY_MINUTES, sanitized).apply()
        _settings.value = loadSettings()
    }

    fun updateOverlayPosition(x: Int, y: Int) {
        prefs.edit()
            .putInt(KEY_OVERLAY_X, x)
            .putInt(KEY_OVERLAY_Y, y)
            .apply()
        _settings.value = loadSettings()
    }

    fun getLatestSettings(): ClickerSettings = _settings.value

    companion object {
        private const val PREFS_NAME = "auto_clicker_prefs"

        private const val KEY_P1_X = "point1_x"
        private const val KEY_P1_Y = "point1_y"
        private const val KEY_P1_ENABLED = "point1_enabled"
        private const val KEY_P1_COUNT = "point1_count"
        private const val KEY_P1_INTERVAL = "point1_interval"

        private const val KEY_P2_X = "point2_x"
        private const val KEY_P2_Y = "point2_y"
        private const val KEY_P2_ENABLED = "point2_enabled"
        private const val KEY_P2_COUNT = "point2_count"
        private const val KEY_P2_INTERVAL = "point2_interval"

        private const val KEY_P3_X = "point3_x"
        private const val KEY_P3_Y = "point3_y"
        private const val KEY_P3_ENABLED = "point3_enabled"
        private const val KEY_P3_COUNT = "point3_count"
        private const val KEY_P3_INTERVAL = "point3_interval"

        private const val KEY_CYCLE_DELAY_MINUTES = "cycle_delay_minutes"

        private const val KEY_OVERLAY_X = "overlay_x"
        private const val KEY_OVERLAY_Y = "overlay_y"

        @Volatile
        private var INSTANCE: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(context).also { INSTANCE = it }
            }
        }
    }
}
