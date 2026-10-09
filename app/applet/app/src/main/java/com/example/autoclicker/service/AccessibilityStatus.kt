package com.example.autoclicker.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

object AccessibilityStatus {
    fun isEnabledInSystem(context: Context): Boolean {
        // 1) Через AccessibilityManager — самый надёжный способ
        try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            if (am != null) {
                val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                val target = ComponentName(context, AutoClickAccessibilityService::class.java)
                val hit = enabled.any { info ->
                    val si = info.resolveInfo?.serviceInfo
                    si != null &&
                        si.packageName == target.packageName &&
                        si.name == target.className
                }
                if (hit) return true
            }
        } catch (_: Exception) {
        }

        // 2) Через Settings.Secure
        return try {
            val raw = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val target = ComponentName(context, AutoClickAccessibilityService::class.java)
            val flat = target.flattenToString()
            val short = target.flattenToShortString()
            val pkg = target.packageName
            val cls = target.className
            raw.split(':').any { part ->
                val p = part.trim()
                if (p.isEmpty()) return@any false
                p.equals(flat, ignoreCase = true) ||
                    p.equals(short, ignoreCase = true) ||
                    (p.contains(pkg, ignoreCase = true) && p.contains(cls.substringAfterLast('.'), ignoreCase = true))
            }
        } catch (_: Exception) {
            false
        }
    }
}
