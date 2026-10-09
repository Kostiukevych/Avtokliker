package com.example.autoclicker.service

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

object AccessibilityStatus {
    @Volatile
    var isRunning: Boolean = false
        private set

    fun setRunning(running: Boolean) {
        isRunning = running
    }

    fun isEnabledInSystem(context: Context): Boolean {
        return try {
            val enabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            ) == 1
            if (!enabled) return false
            val raw = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val target = ComponentName(context, AutoClickAccessibilityService::class.java)
            raw.split(':').any { part ->
                val cn = ComponentName.unflattenFromString(part.trim())
                cn != null && cn == target
            }
        } catch (_: Exception) {
            false
        }
    }
}
