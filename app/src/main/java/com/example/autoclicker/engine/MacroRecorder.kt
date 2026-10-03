package com.example.autoclicker.engine

import android.os.SystemClock
import android.view.MotionEvent
import com.example.autoclicker.data.MacroPoint
import com.example.autoclicker.data.MacroStroke
import com.example.autoclicker.data.MultitouchGroup
import com.example.autoclicker.data.RecordedMacro
import com.example.autoclicker.data.RecordedTouchEvent

/**
 * Рекордер макроса.
 * Записывает все MotionEvent с учетом настоящего мультитача (pointerId, pointerIndex,
 * ACTION_DOWN, ACTION_POINTER_DOWN, ACTION_MOVE, ACTION_POINTER_UP, ACTION_UP, координаты и время).
 * Группирует одновременно касающиеся пальцы в параллельные multitouch-группы.
 */
class MacroRecorder {

    private var recordingStartUptimeMs: Long = 0L
    private var isRecording = false

    private val rawEvents = mutableListOf<RecordedTouchEvent>()
    private val activeStrokes = mutableMapOf<Int, ActiveStroke>()
    private val completedStrokes = mutableListOf<MacroStroke>()
    private var nextStrokeId = 1

    private class ActiveStroke(
        val strokeId: Int,
        val pointerId: Int,
        val startTimeMs: Long,
        val points: MutableList<MacroPoint> = mutableListOf()
    )

    fun start() {
        reset()
        recordingStartUptimeMs = SystemClock.uptimeMillis()
        isRecording = true
    }

    fun isRecordingActive(): Boolean = isRecording

    fun getRecordedEventsCount(): Int = rawEvents.size

    fun getRecordedStrokesCount(): Int = completedStrokes.size + activeStrokes.size

    fun getElapsedTimeMs(): Long {
        if (!isRecording) return 0L
        return SystemClock.uptimeMillis() - recordingStartUptimeMs
    }

    /**
     * Обрабатывает MotionEvent от полноэкранного touchable Overlay.
     * Не передает события дальше (полный перехват).
     */
    fun processTouchEvent(event: MotionEvent) {
        if (!isRecording) return

        val nowMs = maxOf(0L, SystemClock.uptimeMillis() - recordingStartUptimeMs)
        val actionMasked = event.actionMasked
        val actionIndex = event.actionIndex

        when (actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val pointerId = event.getPointerId(actionIndex)
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)
                recordRawEvent(pointerId, actionIndex, MotionEvent.ACTION_DOWN, x, y, nowMs)
                startStroke(pointerId, x, y, nowMs)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerId = event.getPointerId(actionIndex)
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)
                recordRawEvent(pointerId, actionIndex, MotionEvent.ACTION_POINTER_DOWN, x, y, nowMs)
                startStroke(pointerId, x, y, nowMs)
            }

            MotionEvent.ACTION_MOVE -> {
                val pointerCount = event.pointerCount
                for (i in 0 until pointerCount) {
                    val pointerId = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)
                    recordRawEvent(pointerId, i, MotionEvent.ACTION_MOVE, x, y, nowMs)
                    appendPointToStroke(pointerId, x, y, nowMs)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = event.getPointerId(actionIndex)
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)
                recordRawEvent(pointerId, actionIndex, MotionEvent.ACTION_POINTER_UP, x, y, nowMs)
                endStroke(pointerId, x, y, nowMs)
            }

            MotionEvent.ACTION_UP -> {
                val pointerId = event.getPointerId(actionIndex)
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)
                recordRawEvent(pointerId, actionIndex, MotionEvent.ACTION_UP, x, y, nowMs)
                endStroke(pointerId, x, y, nowMs)
            }

            MotionEvent.ACTION_CANCEL -> {
                for (pointerId in activeStrokes.keys.toList()) {
                    endStroke(pointerId, 0f, 0f, nowMs)
                }
            }
        }
    }

    private fun recordRawEvent(
        pointerId: Int,
        pointerIndex: Int,
        action: Int,
        x: Float,
        y: Float,
        timestampMs: Long
    ) {
        rawEvents.add(
            RecordedTouchEvent(
                pointerId = pointerId,
                pointerIndex = pointerIndex,
                action = action,
                x = x,
                y = y,
                timestampMs = timestampMs
            )
        )
    }

    private fun startStroke(pointerId: Int, x: Float, y: Float, timeMs: Long) {
        // Завершаем предыдущий штрих для этого pointerId, если он остался открыт
        if (activeStrokes.containsKey(pointerId)) {
            endStroke(pointerId, x, y, timeMs)
        }
        val stroke = ActiveStroke(
            strokeId = nextStrokeId++,
            pointerId = pointerId,
            startTimeMs = timeMs
        )
        stroke.points.add(MacroPoint(x, y, 0L))
        activeStrokes[pointerId] = stroke
    }

    private fun appendPointToStroke(pointerId: Int, x: Float, y: Float, timeMs: Long) {
        val stroke = activeStrokes[pointerId] ?: return
        val lastPoint = stroke.points.lastOrNull()
        val timeOffset = maxOf(0L, timeMs - stroke.startTimeMs)

        // Исключаем микро-дребезг (если координаты не изменились)
        if (lastPoint == null || kotlin.math.hypot((x - lastPoint.x).toDouble(), (y - lastPoint.y).toDouble()) >= 1.5 ||
            (timeOffset - lastPoint.timeOffsetMs) >= 20
        ) {
            stroke.points.add(MacroPoint(x, y, timeOffset))
        }
    }

    private fun endStroke(pointerId: Int, x: Float, y: Float, timeMs: Long) {
        val active = activeStrokes.remove(pointerId) ?: return
        if (x > 0f || y > 0f) {
            val timeOffset = maxOf(0L, timeMs - active.startTimeMs)
            active.points.add(MacroPoint(x, y, timeOffset))
        }

        val duration = maxOf(40L, timeMs - active.startTimeMs)
        val stroke = MacroStroke(
            strokeId = active.strokeId,
            pointerId = active.pointerId,
            startTimeMs = active.startTimeMs,
            durationMs = duration,
            points = active.points.toList()
        )
        completedStrokes.add(stroke)
    }

    /**
     * Завершает запись и компилирует все жесты в параллельные multitouch-группы.
     */
    fun finishRecording(): RecordedMacro {
        val endTimeMs = maxOf(0L, SystemClock.uptimeMillis() - recordingStartUptimeMs)
        // Закрываем все еще открытые штрихи
        for (pointerId in activeStrokes.keys.toList()) {
            endStroke(pointerId, 0f, 0f, endTimeMs)
        }
        isRecording = false

        val sortedStrokes = completedStrokes.sortedBy { it.startTimeMs }
        val groups = buildMultitouchGroups(sortedStrokes)

        val totalDuration = if (sortedStrokes.isNotEmpty()) {
            val lastEnd = sortedStrokes.maxOf { it.startTimeMs + it.durationMs }
            maxOf(endTimeMs, lastEnd)
        } else {
            endTimeMs
        }

        return RecordedMacro(
            id = "macro_${System.currentTimeMillis()}",
            name = "Запись ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}",
            totalDurationMs = totalDuration,
            strokes = sortedStrokes,
            multitouchGroups = groups,
            rawEventsCount = rawEvents.size,
            createdAt = System.currentTimeMillis()
        )
    }

    /**
     * Группирует штрихи, выполняющиеся одновременно (multitouch), в одну параллельную группу.
     */
    private fun buildMultitouchGroups(strokes: List<MacroStroke>): List<MultitouchGroup> {
        if (strokes.isEmpty()) return emptyList()

        val groups = mutableListOf<MutableList<MacroStroke>>()
        var currentGroup = mutableListOf<MacroStroke>()
        var groupEndMs = -1L

        for (stroke in strokes) {
            val strokeEnd = stroke.startTimeMs + stroke.durationMs
            if (currentGroup.isEmpty()) {
                currentGroup.add(stroke)
                groupEndMs = strokeEnd
            } else {
                // Если штрих начинается до окончания текущей группы или с минимальным зазором <= 50мс,
                // они считаются параллельной мультитач-группой.
                if (stroke.startTimeMs <= groupEndMs + 50L) {
                    currentGroup.add(stroke)
                    groupEndMs = maxOf(groupEndMs, strokeEnd)
                } else {
                    groups.add(currentGroup)
                    currentGroup = mutableListOf(stroke)
                    groupEndMs = strokeEnd
                }
            }
        }
        if (currentGroup.isNotEmpty()) {
            groups.add(currentGroup)
        }

        return groups.mapIndexed { index, list ->
            val gStart = list.minOf { it.startTimeMs }
            val gEnd = list.maxOf { it.startTimeMs + it.durationMs }
            MultitouchGroup(
                groupId = index + 1,
                startTimeMs = gStart,
                durationMs = maxOf(40L, gEnd - gStart),
                strokes = list
            )
        }
    }

    fun reset() {
        isRecording = false
        rawEvents.clear()
        activeStrokes.clear()
        completedStrokes.clear()
        nextStrokeId = 1
        recordingStartUptimeMs = 0L
    }
}
