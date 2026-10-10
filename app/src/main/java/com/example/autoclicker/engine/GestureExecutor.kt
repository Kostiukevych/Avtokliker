package com.example.autoclicker.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.service.AccessibilityServiceHolder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Исполнитель жестов.
 * Отвечает ТОЛЬКО за формирование и отправку жестов в Android AccessibilityService.
 */
class GestureExecutor {

    /**
     * Выполняет короткое нажатие (TAP) по заданным координатам экрана.
     *
     * @param x Координата X
     * @param y Координата Y
     * @param duration Длительность касания в миллисекундах (по умолчанию 50ms)
     * @return true если жест успешно выполнен, false при ошибке или отмене
     */
    suspend fun performTap(x: Float, y: Float, duration: Long = 80L): Boolean {
        val service = AccessibilityServiceHolder.service.value
        if (service == null) {
            EventLogManager.log(
                EventLogManager.TAG_GESTURE,
                "ERROR: AccessibilityService недоступен или отключен в настройках системы",
                isError = true
            )
            return false
        }

        if (x <= 0f && y <= 0f) {
            EventLogManager.log(
                EventLogManager.TAG_GESTURE,
                "ERROR: Некорректные координаты клика ($x, $y)",
                isError = true
            )
            return false
        }

        // Ограничиваем ожидание завершения жеста таймаутом в 2000 мс
        val result = withTimeoutOrNull(2000L) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                try {
                    val path = Path().apply {
                        moveTo(x, y)
                        lineTo(x, y + 1f)
                    }
                    val strokeDuration = duration.coerceIn(40L, 200L)
                    val stroke = GestureDescription.StrokeDescription(path, 0L, strokeDuration)
                    val gesture = GestureDescription.Builder()
                        .addStroke(stroke)
                        .build()

                    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

                    val callback = object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            EventLogManager.log(
                                EventLogManager.TAG_GESTURE,
                                "GESTURE: completed на (${x.toInt()}, ${y.toInt()}) [длительность ${strokeDuration}мс]"
                            )
                            if (continuation.isActive) {
                                continuation.resume(true)
                            }
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            EventLogManager.log(
                                EventLogManager.TAG_GESTURE,
                                "GESTURE: cancelled на (${x.toInt()}, ${y.toInt()}) — жест отменен системой",
                                isError = true
                            )
                            if (continuation.isActive) {
                                continuation.resume(false)
                            }
                        }
                    }

                    val dispatched = service.dispatchGesture(gesture, callback, mainHandler)
                    if (!dispatched) {
                        EventLogManager.log(
                            EventLogManager.TAG_GESTURE,
                            "GESTURE: rejected — dispatchGesture вернул false. Проверьте разрешение жестов у сервиса.",
                            isError = true
                        )
                        if (continuation.isActive) {
                            continuation.resume(false)
                        }
                    }
                } catch (e: Exception) {
                    EventLogManager.log(
                        EventLogManager.TAG_GESTURE,
                        "ERROR: Exception in dispatchGesture: ${e.message}",
                        isError = true
                    )
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }
            }
        }

        return result ?: run {
            EventLogManager.log(
                EventLogManager.TAG_GESTURE,
                "ERROR: Gesture execution timed out",
                isError = true
            )
            false
        }
    }

    /**
     * Выполняет воспроизведение записанного макроса, включая параллельные multitouch-группы.
     *
     * @param macro Записанный макрос
     * @param isCancelled Функция проверки отмены выполнения
     * @return true если все группы макроса успешно воспроизведены
     */
    suspend fun performMacro(
        macro: com.example.autoclicker.data.RecordedMacro,
        isCancelled: () -> Boolean = { false }
    ): Boolean {
        val service = AccessibilityServiceHolder.service.value
        if (service == null) {
            EventLogManager.log(
                EventLogManager.TAG_GESTURE,
                "ERROR: AccessibilityService недоступен для воспроизведения макроса",
                isError = true
            )
            return false
        }

        if (macro.multitouchGroups.isEmpty()) {
            EventLogManager.log(
                EventLogManager.TAG_GESTURE,
                "ERROR: Макрос пуст, нет жестов для воспроизведения",
                isError = true
            )
            return false
        }

        EventLogManager.log(
            EventLogManager.TAG_GESTURE,
            "MACRO PLAY START: ${macro.strokes.size} жестов в ${macro.multitouchGroups.size} группах (${macro.formattedDuration})"
        )

        var lastGroupEndMs = 0L

        for ((index, group) in macro.multitouchGroups.withIndex()) {
            if (isCancelled()) {
                EventLogManager.log(EventLogManager.TAG_GESTURE, "MACRO PLAY CANCELLED")
                return false
            }

            // Задержка между группами касаний, соответствующая реальному времени записи
            val delayBeforeGroup = maxOf(0L, group.startTimeMs - lastGroupEndMs)
            if (delayBeforeGroup > 0L) {
                kotlinx.coroutines.delay(delayBeforeGroup)
            }

            if (isCancelled()) return false

            val success = executeMultitouchGroup(service, group, index + 1, macro.multitouchGroups.size)
            if (!success) {
                EventLogManager.log(
                    EventLogManager.TAG_GESTURE,
                    "MACRO GROUP #${index + 1} FAILED: Ошибка воспроизведения группы",
                    isError = true
                )
                return false
            }

            lastGroupEndMs = group.startTimeMs + group.durationMs
        }

        EventLogManager.log(EventLogManager.TAG_GESTURE, "MACRO PLAY FINISHED: Успешно завершено")
        return true
    }

    private suspend fun executeMultitouchGroup(
        service: AccessibilityService,
        group: com.example.autoclicker.data.MultitouchGroup,
        groupIndex: Int,
        totalGroups: Int
    ): Boolean {
        if (group.strokes.isEmpty()) return true

        val timeout = group.durationMs + 3000L

        return withTimeoutOrNull(timeout) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                try {
                    val builder = GestureDescription.Builder()

                    for (stroke in group.strokes) {
                        val path = Path().apply {
                            val pts = stroke.points
                            if (pts.isEmpty()) {
                                moveTo(stroke.startX, stroke.startY)
                                lineTo(stroke.startX, stroke.startY + 1f)
                            } else {
                                moveTo(pts[0].x, pts[0].y)
                                for (i in 1 until pts.size) {
                                    lineTo(pts[i].x, pts[i].y)
                                }
                                if (pts.size == 1) {
                                    lineTo(pts[0].x, pts[0].y + 1f)
                                }
                            }
                        }

                        val strokeStart = maxOf(0L, stroke.startTimeMs - group.startTimeMs)
                        val strokeDur = stroke.durationMs.coerceIn(40L, 59_000L)
                        val strokeDesc = GestureDescription.StrokeDescription(path, strokeStart, strokeDur)
                        builder.addStroke(strokeDesc)
                    }

                    val gesture = builder.build()
                    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

                    val callback = object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            EventLogManager.log(
                                EventLogManager.TAG_GESTURE,
                                "MACRO GROUP $groupIndex/$totalGroups: OK (${group.strokes.size} параллельных пальцев, ${group.durationMs}мс)"
                            )
                            if (continuation.isActive) {
                                continuation.resume(true)
                            }
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            EventLogManager.log(
                                EventLogManager.TAG_GESTURE,
                                "MACRO GROUP $groupIndex/$totalGroups: CANCELLED системой",
                                isError = true
                            )
                            if (continuation.isActive) {
                                continuation.resume(false)
                            }
                        }
                    }

                    val dispatched = service.dispatchGesture(gesture, callback, mainHandler)
                    if (!dispatched) {
                        EventLogManager.log(
                            EventLogManager.TAG_GESTURE,
                            "MACRO GROUP $groupIndex/$totalGroups: REJECTED dispatchGesture",
                            isError = true
                        )
                        if (continuation.isActive) {
                            continuation.resume(false)
                        }
                    }
                } catch (e: Exception) {
                    EventLogManager.log(
                        EventLogManager.TAG_GESTURE,
                        "ERROR in executeMultitouchGroup: ${e.message}",
                        isError = true
                    )
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }
            }
        } ?: false
    }
}
