package com.example.autoclicker.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Репозиторий хранения настроек в SharedPreferences.
 * Сохраняет все координаты, таймеры, ориентацию и параметры точек (1..10) на диск.
 */
class SettingsRepository private constructor(val context: Context) {

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

        fun loadExtraPoint(id: Int): ClickPoint {
            return ClickPoint(
                id = id,
                name = "Точка $id",
                x = prefs.getFloat("point${id}_x", 0f),
                y = prefs.getFloat("point${id}_y", 0f),
                enabled = prefs.getBoolean("point${id}_enabled", false),
                clickCount = prefs.getInt("point${id}_count", 1),
                intervalSec = prefs.getInt("point${id}_interval", 2)
            )
        }

        val cycleDelayMinutes = prefs.getInt(KEY_CYCLE_DELAY_MINUTES, 7)
        val overlayX = prefs.getInt(KEY_OVERLAY_X, 50)
        val overlayY = prefs.getInt(KEY_OVERLAY_Y, 150)
        val pointsOrientation = prefs.getInt(KEY_POINTS_ORIENTATION, 0)
        val pointsScreenWidth = prefs.getInt(KEY_POINTS_SCREEN_WIDTH, 0)
        val pointsScreenHeight = prefs.getInt(KEY_POINTS_SCREEN_HEIGHT, 0)
        val isSmartMode = prefs.getBoolean(KEY_SMART_MODE, false)
        val isDebugScreenshots = prefs.getBoolean(KEY_DEBUG_SCREENSHOTS, false)

        fun loadSwipe(id: Int): SwipeAction {
            return SwipeAction(
                id = id,
                startX = prefs.getFloat("swipe${id}_start_x", 0f),
                startY = prefs.getFloat("swipe${id}_start_y", 0f),
                endX = prefs.getFloat("swipe${id}_end_x", 0f),
                endY = prefs.getFloat("swipe${id}_end_y", 0f),
                durationMs = prefs.getLong("swipe${id}_duration_ms", 300L),
                intervalSec = prefs.getInt("swipe${id}_interval_sec", 10),
                enabled = prefs.getBoolean("swipe${id}_enabled", false)
            )
        }

        val isSwipesEnabled = prefs.getBoolean(KEY_SWIPES_ENABLED, false)
        val swipes = listOf(loadSwipe(1), loadSwipe(2), loadSwipe(3))

        val macroJson = prefs.getString(KEY_MACRO_JSON, null)
        val recordedMacro = RecordedMacro.fromJson(macroJson)
        val macroRepeatCount = prefs.getInt(KEY_MACRO_REPEAT_COUNT, 1)
        val macroIntervalSec = prefs.getInt(KEY_MACRO_INTERVAL_SEC, 0)
        val neonBrightness = prefs.getInt(KEY_NEON_BRIGHTNESS, 85)
        com.example.autoclicker.ui.theme.NeonTheme.brightness = neonBrightness / 100f

        return ClickerSettings(
            point1 = p1,
            point2 = p2,
            point3 = p3,
            point4 = loadExtraPoint(4),
            point5 = loadExtraPoint(5),
            point6 = loadExtraPoint(6),
            point7 = loadExtraPoint(7),
            point8 = loadExtraPoint(8),
            point9 = loadExtraPoint(9),
            point10 = loadExtraPoint(10),
            cycleDelayMinutes = cycleDelayMinutes,
            overlayX = overlayX,
            overlayY = overlayY,
            pointsOrientation = pointsOrientation,
            pointsScreenWidth = pointsScreenWidth,
            pointsScreenHeight = pointsScreenHeight,
            isSmartMode = isSmartMode,
            isDebugScreenshots = isDebugScreenshots,
            isSwipesEnabled = isSwipesEnabled,
            swipes = swipes,
            recordedMacro = recordedMacro,
            macroRepeatCount = macroRepeatCount,
            macroIntervalSec = macroIntervalSec,
            neonBrightness = neonBrightness
        )
    }

    fun updatePointCoordinates(
        pointId: Int,
        x: Float,
        y: Float,
        orientation: Int = 0,
        screenWidth: Int = 0,
        screenHeight: Int = 0
    ) {
        val editor = prefs.edit()
        if (pointId in 1..10) {
            editor.putFloat("point${pointId}_x", x)
            editor.putFloat("point${pointId}_y", y)
        }
        if (orientation > 0) {
            editor.putInt(KEY_POINTS_ORIENTATION, orientation)
        }
        if (screenWidth > 0 && screenHeight > 0) {
            editor.putInt(KEY_POINTS_SCREEN_WIDTH, screenWidth)
            editor.putInt(KEY_POINTS_SCREEN_HEIGHT, screenHeight)
        }
        editor.apply()
        _settings.value = loadSettings()
    }

    fun updatePoint(pointId: Int, x: Float, y: Float) = updatePointCoordinates(pointId, x, y)

    fun updatePointConfig(pointId: Int, enabled: Boolean, count: Int, interval: Int) {
        val editor = prefs.edit()
        val safeCount = count.coerceIn(1, 10)
        val safeInterval = interval.coerceIn(1, 30)
        if (pointId in 1..10) {
            editor.putBoolean("point${pointId}_enabled", enabled)
            editor.putInt("point${pointId}_count", safeCount)
            editor.putInt("point${pointId}_interval", safeInterval)
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

    fun updateSmartMode(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_SMART_MODE, enabled)
            .apply()
        _settings.value = loadSettings()
    }

    fun updateDebugScreenshots(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_DEBUG_SCREENSHOTS, enabled)
            .apply()
        _settings.value = loadSettings()
    }

    fun updateSwipesMasterEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_SWIPES_ENABLED, enabled)
            .apply()
        _settings.value = loadSettings()
    }

    fun updateSwipeCoordinates(
        swipeId: Int,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float
    ) {
        if (swipeId in 1..3) {
            prefs.edit()
                .putFloat("swipe${swipeId}_start_x", startX)
                .putFloat("swipe${swipeId}_start_y", startY)
                .putFloat("swipe${swipeId}_end_x", endX)
                .putFloat("swipe${swipeId}_end_y", endY)
                .apply()
            _settings.value = loadSettings()
        }
    }

    fun updateSwipeConfig(
        swipeId: Int,
        enabled: Boolean,
        durationMs: Long,
        intervalSec: Int
    ) {
        if (swipeId in 1..3) {
            val safeDuration = durationMs.coerceIn(100L, 1500L)
            val safeInterval = intervalSec.coerceIn(1, 600)
            prefs.edit()
                .putBoolean("swipe${swipeId}_enabled", enabled)
                .putLong("swipe${swipeId}_duration_ms", safeDuration)
                .putInt("swipe${swipeId}_interval_sec", safeInterval)
                .apply()
            _settings.value = loadSettings()
        }
    }

    fun saveMacro(macro: RecordedMacro) {
        prefs.edit()
            .putString(KEY_MACRO_JSON, macro.toJson())
            .apply()
        _settings.value = loadSettings()
    }

    fun clearMacro() {
        prefs.edit()
            .remove(KEY_MACRO_JSON)
            .apply()
        _settings.value = loadSettings()
    }

    fun updateMacroConfig(repeatCount: Int, intervalSec: Int) {
        prefs.edit()
            .putInt(KEY_MACRO_REPEAT_COUNT, repeatCount.coerceIn(1, 100))
            .putInt(KEY_MACRO_INTERVAL_SEC, intervalSec.coerceIn(0, 60))
            .apply()
        _settings.value = loadSettings()
    }

    fun updateNeonBrightness(brightness: Int) {
        val b = brightness.coerceIn(0, 100)
        prefs.edit().putInt(KEY_NEON_BRIGHTNESS, b).apply()
        com.example.autoclicker.ui.theme.NeonTheme.brightness = b / 100f
        _settings.value = loadSettings()
    }

    fun getLatestSettings(): ClickerSettings = _settings.value

    companion object {
        private const val PREFS_NAME = "auto_clicker_prefs"
        private const val KEY_NEON_BRIGHTNESS = "neon_brightness"

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

        private const val KEY_POINTS_ORIENTATION = "points_orientation"
        private const val KEY_POINTS_SCREEN_WIDTH = "points_screen_width"
        private const val KEY_POINTS_SCREEN_HEIGHT = "points_screen_height"

        private const val KEY_SMART_MODE = "is_smart_mode"
        private const val KEY_DEBUG_SCREENSHOTS = "is_debug_screenshots"
        private const val KEY_SWIPES_ENABLED = "is_swipes_enabled"

        private const val KEY_MACRO_JSON = "macro_json"
        private const val KEY_MACRO_REPEAT_COUNT = "macro_repeat_count"
        private const val KEY_MACRO_INTERVAL_SEC = "macro_interval_sec"

        @Volatile
        private var INSTANCE: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
