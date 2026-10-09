package com.example.autoclicker.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

object AccessibilityStatus {
    @Volatile
    var isRunning: Boolean = false
        private set

    fun setRunning(running: Boolean) {
        isRunning = running
    }

    /**
     * Сервис включён в системных настройках (не обязательно уже привязан к процессу).
     */
    fun isEnabledInSystem(context: Context): Boolean {
        try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            if (am != null) {
                val list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                val target = ComponentName(context, AutoClickAccessibilityService::class.java)
                val hit = list.any { info ->
                    val si = info.resolveInfo?.serviceInfo ?: return@any false
                    si.packageName == target.packageName && si.name == target.className
                }
                if (hit) return true
            }
        } catch (_: Exception) {
        }
        return try {
            val raw = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val target = ComponentName(context, AutoClickAccessibilityService::class.java)
            val flat = target.flattenToString()
            val short = target.flattenToShortString()
            raw.split(':').any { part ->
                val p = part.trim()
                p.equals(flat, true) || p.equals(short, true) ||
                    (p.contains(target.packageName) && p.contains(target.className.substringAfterLast('.')))
            }
        } catch (_: Exception) {
            false
        }
    }
}
