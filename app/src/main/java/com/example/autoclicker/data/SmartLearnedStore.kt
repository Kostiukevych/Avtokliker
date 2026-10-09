package com.example.autoclicker.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Запомненные масштаб и координаты кнопок умного режима для конкретного размера экрана.
 * Живёт в SharedPreferences — переживает перезапуск процесса.
 */
object SmartLearnedStore {
    data class Learned(
        val multiplier: Float,
        val xFrac: Float,
        val yFrac: Float,
        val score: Float
    )

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences("smart_learned", Context.MODE_PRIVATE)
        }
    }

    private fun key(screenW: Int, screenH: Int, ruleId: String) = "${screenW}x${screenH}:$ruleId"

    fun get(screenW: Int, screenH: Int, ruleId: String): Learned? {
        val p = prefs ?: return null
        val raw = p.getString(key(screenW, screenH, ruleId), null) ?: return null
        val parts = raw.split(';')
        if (parts.size < 4) return null
        return try {
            Learned(
                multiplier = parts[0].toFloat(),
                xFrac = parts[1].toFloat(),
                yFrac = parts[2].toFloat(),
                score = parts[3].toFloat()
            )
        } catch (_: Exception) {
            null
        }
    }

    fun put(
        screenW: Int,
        screenH: Int,
        ruleId: String,
        multiplier: Float,
        xFrac: Float,
        yFrac: Float,
        score: Float
    ) {
        val p = prefs ?: return
        p.edit()
            .putString(
                key(screenW, screenH, ruleId),
                "$multiplier;$xFrac;$yFrac;$score"
            )
            .apply()
    }

    fun clear() {
        prefs?.edit()?.clear()?.apply()
    }
}
