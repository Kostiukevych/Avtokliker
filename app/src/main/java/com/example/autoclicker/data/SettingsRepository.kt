package com.example.autoclicker.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Репозиторий хранения настроек в SharedPreferences.
 */
class SettingsRepository(val context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<ClickerSettings> = _settings.asStateFlow()

    private fun loadSettings(): ClickerSettings {
        val overlayX = prefs.getInt(KEY_OVERLAY_X, 50)
        val overlayY = prefs.getInt(KEY_OVERLAY_Y, 150)
        val isSmartMode = prefs.getBoolean(KEY_SMART_MODE, true)
        val isDebugScreenshots = prefs.getBoolean(KEY_DEBUG_SCREENSHOTS, false)

        val macroJson = prefs.getString(KEY_MACRO_JSON, null)
        val recordedMacro = RecordedMacro.fromJson(macroJson)
        val macroRepeatCount = prefs.getInt(KEY_MACRO_REPEAT_COUNT, 1)
        val macroIntervalSec = prefs.getInt(KEY_MACRO_INTERVAL_SEC, 0)
        val neonBrightness = prefs.getInt(KEY_NEON_BRIGHTNESS, 85)
        com.example.autoclicker.ui.theme.NeonTheme.brightness = neonBrightness / 100f
        val scheduleEnabled = prefs.getBoolean(KEY_SCHEDULE_ENABLED, false)
        val scheduleAtEpochMs = prefs.getLong(KEY_SCHEDULE_AT, 0L)
        val scheduleTarget = prefs.getString(KEY_SCHEDULE_TARGET, "all") ?: "all"

        return ClickerSettings(
            overlayX = overlayX,
            overlayY = overlayY,
            isSmartMode = isSmartMode,
            isDebugScreenshots = isDebugScreenshots,
            recordedMacro = recordedMacro,
            macroRepeatCount = macroRepeatCount,
            macroIntervalSec = macroIntervalSec,
            neonBrightness = neonBrightness,
            scheduleEnabled = scheduleEnabled,
            scheduleAtEpochMs = scheduleAtEpochMs,
            scheduleTarget = scheduleTarget
        )
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
            .putInt(KEY_MACRO_REPEAT_COUNT, repeatCount.coerceIn(0, 999))
            .putInt(KEY_MACRO_INTERVAL_SEC, intervalSec.coerceIn(0, 300))
            .apply()
        _settings.value = loadSettings()
    }

    fun updateSchedule(enabled: Boolean, atEpochMs: Long, target: String = "all") {
        prefs.edit()
            .putBoolean(KEY_SCHEDULE_ENABLED, enabled)
            .putLong(KEY_SCHEDULE_AT, if (enabled) atEpochMs else 0L)
            .putString(KEY_SCHEDULE_TARGET, target)
            .apply()
        _settings.value = loadSettings()
        try {
            if (enabled && atEpochMs > 0L) {
                com.example.autoclicker.service.ScheduleManager.schedule(context, atEpochMs)
            } else {
                com.example.autoclicker.service.ScheduleManager.cancel(context)
            }
        } catch (_: Exception) {
        }
    }

    fun clearSchedule() {
        updateSchedule(false, 0L, "all")
    }

    fun setRunActive(active: Boolean) {
        prefs.edit().putBoolean(KEY_RUN_ACTIVE, active).apply()
    }

    fun isRunActive(): Boolean = prefs.getBoolean(KEY_RUN_ACTIVE, false)

    fun updateNeonBrightness(brightness: Int) {
        val b = brightness.coerceIn(0, 100)
        prefs.edit().putInt(KEY_NEON_BRIGHTNESS, b).apply()
        com.example.autoclicker.ui.theme.NeonTheme.brightness = b / 100f
        _settings.value = loadSettings()
    }

    fun updateRunSmart(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SMART_MODE, enabled).apply()
        _settings.value = loadSettings()
    }

    // ---------- Сбросы ----------

    /** Сброс флагов умного режима (режим и выбор конфига сбрасывает ResetManager). */
    fun resetSmartFlags() {
        prefs.edit()
            .remove(KEY_SMART_MODE)
            .remove(KEY_DEBUG_SCREENSHOTS)
            .apply()
        _settings.value = loadSettings()
    }

    fun resetNeonBrightness() {
        prefs.edit().remove(KEY_NEON_BRIGHTNESS).apply()
        com.example.autoclicker.ui.theme.NeonTheme.brightness = 85 / 100f
        _settings.value = loadSettings()
    }

    fun getLatestSettings(): ClickerSettings = _settings.value

    companion object {
        private const val PREFS_NAME = "auto_clicker_prefs"
        private const val KEY_NEON_BRIGHTNESS = "neon_brightness"

        private const val KEY_OVERLAY_X = "overlay_x"
        private const val KEY_OVERLAY_Y = "overlay_y"

        private const val KEY_SMART_MODE = "is_smart_mode"
        private const val KEY_DEBUG_SCREENSHOTS = "is_debug_screenshots"
        private const val KEY_SCHEDULE_ENABLED = "schedule_enabled"
        private const val KEY_SCHEDULE_AT = "schedule_at_epoch_ms"
        private const val KEY_SCHEDULE_TARGET = "schedule_target"
        private const val KEY_RUN_ACTIVE = "run_active"

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
