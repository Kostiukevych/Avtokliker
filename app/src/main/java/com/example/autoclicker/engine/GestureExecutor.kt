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
    suspend fun performTap(x: Float, y: Float, duration: Long = 50L): Boolean {
        val service = AccessibilityServiceHolder.service.value
        if (service == null) {
            EventLogManager.log(
                EventLogManager.TAG_GESTURE,
                "ERROR: AccessibilityService unavailable",
                isError = true
            )
            return false
        }

        if (x < 0f || y < 0f) {
            EventLogManager.log(
                EventLogManager.TAG_GESTURE,
                "ERROR: Некорректные координаты клика: ($x, $y)",
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
                    }
                    val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
                    val gesture = GestureDescription.Builder()
                        .addStroke(stroke)
                        .build()

                    val callback = object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            EventLogManager.log(
                                EventLogManager.TAG_GESTURE,
                                "TAP [X=${x.toInt()}, Y=${y.toInt()}] OK"
                            )
                            if (continuation.isActive) {
                                continuation.resume(true)
                            }
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            EventLogManager.log(
                                EventLogManager.TAG_GESTURE,
                                "ERROR: Gesture dispatch failed (cancelled) at ($x, $y)",
                                isError = true
                            )
                            if (continuation.isActive) {
                                continuation.resume(false)
                            }
                        }
                    }

                    val dispatched = service.dispatchGesture(gesture, callback, null)
                    if (!dispatched) {
                        EventLogManager.log(
                            EventLogManager.TAG_GESTURE,
                            "ERROR: Gesture dispatch rejected by system",
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
}
