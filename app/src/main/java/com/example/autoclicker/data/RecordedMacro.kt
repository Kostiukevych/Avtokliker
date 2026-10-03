package com.example.autoclicker.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Сырое зарегистрированное событие касания экрана.
 */
data class RecordedTouchEvent(
    val pointerId: Int,
    val pointerIndex: Int,
    val action: Int,
    val x: Float,
    val y: Float,
    val timestampMs: Long
)

/**
 * Точка траектории касания пальца.
 */
data class MacroPoint(
    val x: Float,
    val y: Float,
    val timeOffsetMs: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("x", x.toDouble())
        put("y", y.toDouble())
        put("t", timeOffsetMs)
    }

    companion object {
        fun fromJson(json: JSONObject): MacroPoint = MacroPoint(
            x = json.optDouble("x", 0.0).toFloat(),
            y = json.optDouble("y", 0.0).toFloat(),
            timeOffsetMs = json.optLong("t", 0L)
        )
    }
}

/**
 * Один непрерывный жест одного пальца (от DOWN до UP).
 */
data class MacroStroke(
    val strokeId: Int,
    val pointerId: Int,
    val startTimeMs: Long,
    val durationMs: Long,
    val points: List<MacroPoint>
) {
    val startX: Float
        get() = points.firstOrNull()?.x ?: 0f

    val startY: Float
        get() = points.firstOrNull()?.y ?: 0f

    val endX: Float
        get() = points.lastOrNull()?.x ?: startX

    val endY: Float
        get() = points.lastOrNull()?.y ?: startY

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", strokeId)
        put("pid", pointerId)
        put("start", startTimeMs)
        put("dur", durationMs)
        val arr = JSONArray()
        points.forEach { arr.put(it.toJson()) }
        put("pts", arr)
    }

    companion object {
        fun fromJson(json: JSONObject): MacroStroke {
            val strokeId = json.optInt("id", 0)
            val pointerId = json.optInt("pid", 0)
            val startTimeMs = json.optLong("start", 0L)
            val durationMs = json.optLong("dur", 50L)
            val ptsArr = json.optJSONArray("pts") ?: JSONArray()
            val points = mutableListOf<MacroPoint>()
            for (i in 0 until ptsArr.length()) {
                val ptObj = ptsArr.optJSONObject(i) ?: continue
                points.add(MacroPoint.fromJson(ptObj))
            }
            return MacroStroke(strokeId, pointerId, startTimeMs, durationMs, points)
        }
    }
}

/**
 * Группа одновременно выполняющихся параллельных штрихов (Multitouch).
 */
data class MultitouchGroup(
    val groupId: Int,
    val startTimeMs: Long,
    val durationMs: Long,
    val strokes: List<MacroStroke>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("gid", groupId)
        put("start", startTimeMs)
        put("dur", durationMs)
        val arr = JSONArray()
        strokes.forEach { arr.put(it.toJson()) }
        put("strokes", arr)
    }

    companion object {
        fun fromJson(json: JSONObject): MultitouchGroup {
            val groupId = json.optInt("gid", 0)
            val startTimeMs = json.optLong("start", 0L)
            val durationMs = json.optLong("dur", 50L)
            val arr = json.optJSONArray("strokes") ?: JSONArray()
            val strokes = mutableListOf<MacroStroke>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                strokes.add(MacroStroke.fromJson(obj))
            }
            return MultitouchGroup(groupId, startTimeMs, durationMs, strokes)
        }
    }
}

/**
 * Полный сохраненный макрос, содержащий параллельные группы мультитач-жестов.
 */
data class RecordedMacro(
    val id: String = "macro_default",
    val name: String = "Макрос",
    val totalDurationMs: Long = 0L,
    val strokes: List<MacroStroke> = emptyList(),
    val multitouchGroups: List<MultitouchGroup> = emptyList(),
    val rawEventsCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    val isNotEmpty: Boolean
        get() = strokes.isNotEmpty()

    val formattedDuration: String
        get() {
            val totalSec = totalDurationMs / 1000f
            return String.format(java.util.Locale.US, "%.1fс", totalSec)
        }

    fun toJson(): String {
        val root = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("totalDur", totalDurationMs)
            put("rawCount", rawEventsCount)
            put("created", createdAt)

            val strokesArr = JSONArray()
            strokes.forEach { strokesArr.put(it.toJson()) }
            put("strokes", strokesArr)

            val groupsArr = JSONArray()
            multitouchGroups.forEach { groupsArr.put(it.toJson()) }
            put("groups", groupsArr)
        }
        return root.toString()
    }

    companion object {
        fun fromJson(jsonStr: String?): RecordedMacro? {
            if (jsonStr.isNullOrBlank()) return null
            return try {
                val root = JSONObject(jsonStr)
                val id = root.optString("id", "macro_default")
                val name = root.optString("name", "Макрос")
                val totalDurationMs = root.optLong("totalDur", 0L)
                val rawCount = root.optInt("rawCount", 0)
                val created = root.optLong("created", System.currentTimeMillis())

                val strokesArr = root.optJSONArray("strokes") ?: JSONArray()
                val strokes = mutableListOf<MacroStroke>()
                for (i in 0 until strokesArr.length()) {
                    val sObj = strokesArr.optJSONObject(i) ?: continue
                    strokes.add(MacroStroke.fromJson(sObj))
                }

                val groupsArr = root.optJSONArray("groups") ?: JSONArray()
                val groups = mutableListOf<MultitouchGroup>()
                for (i in 0 until groupsArr.length()) {
                    val gObj = groupsArr.optJSONObject(i) ?: continue
                    groups.add(MultitouchGroup.fromJson(gObj))
                }

                RecordedMacro(
                    id = id,
                    name = name,
                    totalDurationMs = totalDurationMs,
                    strokes = strokes,
                    multitouchGroups = groups,
                    rawEventsCount = rawCount,
                    createdAt = created
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
