package com.example.autoclicker.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow



/**
 * Репозиторий хранения настроек джойстиков в SharedPreferences.
 */
class JoystickRepository private constructor(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<JoystickSettings> = _settings.asStateFlow()

    fun getLatest(): JoystickSettings = _settings.value

    private fun load(): JoystickSettings {
        val s1 = StickConfig(
            id = 1,
            x = prefs.getFloat(KEY_J1_X, 0f),
            y = prefs.getFloat(KEY_J1_Y, 0f),
            enabled = prefs.getBoolean(KEY_J1_ENABLED, true),
            radiusPx = prefs.getInt(KEY_J1_RADIUS, 120),
            strength = prefs.getInt(KEY_J1_STRENGTH, 100),
            mode = prefs.getString(KEY_J1_MODE, "hold") ?: "hold",
            angleDeg = prefs.getInt(KEY_J1_ANGLE, 0),
            circlePeriodSec = prefs.getInt(KEY_J1_PERIOD, 4)
        )
        val s2 = StickConfig(
            id = 2,
            x = prefs.getFloat(KEY_J2_X, 0f),
            y = prefs.getFloat(KEY_J2_Y, 0f),
            enabled = prefs.getBoolean(KEY_J2_ENABLED, true),
            radiusPx = prefs.getInt(KEY_J2_RADIUS, 120),
            strength = prefs.getInt(KEY_J2_STRENGTH, 100),
            mode = prefs.getString(KEY_J2_MODE, "hold") ?: "hold",
            angleDeg = prefs.getInt(KEY_J2_ANGLE, 0),
            circlePeriodSec = prefs.getInt(KEY_J2_PERIOD, 4)
        )
        val btn = FireButtonConfig(
            x = prefs.getFloat(KEY_BTN_X, 0f),
            y = prefs.getFloat(KEY_BTN_Y, 0f),
            enabled = prefs.getBoolean(KEY_BTN_ENABLED, true),
            mode = prefs.getString(KEY_BTN_MODE, "tap") ?: "tap",
            intervalMs = prefs.getInt(KEY_BTN_INTERVAL, 400)
        )
        return JoystickSettings(
            masterEnabled = prefs.getBoolean(KEY_MASTER_ENABLED, false),
            autoMimicEnabled = prefs.getBoolean(KEY_AUTO_MIMIC, false),
            screenW = prefs.getInt(KEY_SCREEN_W, 0),
            screenH = prefs.getInt(KEY_SCREEN_H, 0),
            stick1 = s1,
            stick2 = s2,
            button = btn
        )
    }

    private fun save(s: JoystickSettings) {
        prefs.edit().apply {
            putBoolean(KEY_MASTER_ENABLED, s.masterEnabled)
            putBoolean(KEY_AUTO_MIMIC, s.autoMimicEnabled)
            putInt(KEY_SCREEN_W, s.screenW)
            putInt(KEY_SCREEN_H, s.screenH)

            putFloat(KEY_J1_X, s.stick1.x)
            putFloat(KEY_J1_Y, s.stick1.y)
            putBoolean(KEY_J1_ENABLED, s.stick1.enabled)
            putInt(KEY_J1_RADIUS, s.stick1.radiusPx)
            putInt(KEY_J1_STRENGTH, s.stick1.strength)
            putString(KEY_J1_MODE, s.stick1.mode)
            putInt(KEY_J1_ANGLE, s.stick1.angleDeg)
            putInt(KEY_J1_PERIOD, s.stick1.circlePeriodSec)

            putFloat(KEY_J2_X, s.stick2.x)
            putFloat(KEY_J2_Y, s.stick2.y)
            putBoolean(KEY_J2_ENABLED, s.stick2.enabled)
            putInt(KEY_J2_RADIUS, s.stick2.radiusPx)
            putInt(KEY_J2_STRENGTH, s.stick2.strength)
            putString(KEY_J2_MODE, s.stick2.mode)
            putInt(KEY_J2_ANGLE, s.stick2.angleDeg)
            putInt(KEY_J2_PERIOD, s.stick2.circlePeriodSec)

            putFloat(KEY_BTN_X, s.button.x)
            putFloat(KEY_BTN_Y, s.button.y)
            putBoolean(KEY_BTN_ENABLED, s.button.enabled)
            putString(KEY_BTN_MODE, s.button.mode)
            putInt(KEY_BTN_INTERVAL, s.button.intervalMs)
            apply()
        }
        _settings.value = s
    }

    fun setMaster(enabled: Boolean) {
        val cur = _settings.value
        save(cur.copy(masterEnabled = enabled))
    }

    fun setAutoMimic(enabled: Boolean) {
        val cur = _settings.value
        save(cur.copy(autoMimicEnabled = enabled))
    }

    fun setPosition(kind: Int, x: Float, y: Float, screenW: Int, screenH: Int) {
        val cur = _settings.value
        val updated = when (kind) {
            1 -> cur.copy(
                stick1 = cur.stick1.copy(x = x, y = y),
                screenW = if (screenW > 0) screenW else cur.screenW,
                screenH = if (screenH > 0) screenH else cur.screenH
            )
            2 -> cur.copy(
                stick2 = cur.stick2.copy(x = x, y = y),
                screenW = if (screenW > 0) screenW else cur.screenW,
                screenH = if (screenH > 0) screenH else cur.screenH
            )
            3 -> cur.copy(
                button = cur.button.copy(x = x, y = y),
                screenW = if (screenW > 0) screenW else cur.screenW,
                screenH = if (screenH > 0) screenH else cur.screenH
            )
            else -> cur
        }
        save(updated)
    }

    fun updateStick(id: Int, transform: (StickConfig) -> StickConfig) {
        val cur = _settings.value
        val updated = when (id) {
            1 -> cur.copy(stick1 = transform(cur.stick1))
            2 -> cur.copy(stick2 = transform(cur.stick2))
            else -> cur
        }
        save(updated)
    }

    fun resetStick(id: Int) {
        val cur = _settings.value
        val updated = when (id) {
            1 -> cur.copy(stick1 = StickConfig(1))
            2 -> cur.copy(stick2 = StickConfig(2))
            else -> cur
        }
        save(updated)
    }

    fun updateButton(transform: (FireButtonConfig) -> FireButtonConfig) {
        val cur = _settings.value
        save(cur.copy(button = transform(cur.button)))
    }

    fun resetButton() {
        val cur = _settings.value
        save(cur.copy(button = FireButtonConfig()))
    }

    fun resetAll() {
        save(JoystickSettings())
    }

    companion object {
        private const val PREFS_NAME = "autoclicker_joysticks_prefs"

        private const val KEY_MASTER_ENABLED = "master_enabled"
        private const val KEY_AUTO_MIMIC = "auto_mimic_enabled"
        private const val KEY_SCREEN_W = "screen_w"
        private const val KEY_SCREEN_H = "screen_h"

        private const val KEY_J1_X = "j1_x"
        private const val KEY_J1_Y = "j1_y"
        private const val KEY_J1_ENABLED = "j1_enabled"
        private const val KEY_J1_RADIUS = "j1_radius"
        private const val KEY_J1_STRENGTH = "j1_strength"
        private const val KEY_J1_MODE = "j1_mode"
        private const val KEY_J1_ANGLE = "j1_angle"
        private const val KEY_J1_PERIOD = "j1_period"

        private const val KEY_J2_X = "j2_x"
        private const val KEY_J2_Y = "j2_y"
        private const val KEY_J2_ENABLED = "j2_enabled"
        private const val KEY_J2_RADIUS = "j2_radius"
        private const val KEY_J2_STRENGTH = "j2_strength"
        private const val KEY_J2_MODE = "j2_mode"
        private const val KEY_J2_ANGLE = "j2_angle"
        private const val KEY_J2_PERIOD = "j2_period"

        private const val KEY_BTN_X = "btn_x"
        private const val KEY_BTN_Y = "btn_y"
        private const val KEY_BTN_ENABLED = "btn_enabled"
        private const val KEY_BTN_MODE = "btn_mode"
        private const val KEY_BTN_INTERVAL = "btn_interval"

        @Volatile
        private var INSTANCE: JoystickRepository? = null

        fun getInstance(context: Context): JoystickRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: JoystickRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
