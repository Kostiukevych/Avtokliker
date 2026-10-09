package com.example.autoclicker.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.service.AccessibilityServiceHolder
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Эмуляция экранного джойстика через AccessibilityService (Android 8+, API 26+).
 *
 * Как это работает:
 *  - Первый жест кладёт «палец» в центр круга джойстика и ведёт его к краю.
 *  - Каждый следующий жест ПРОДОЛЖАЕТ тот же штрих (continueStroke), поэтому палец
 *    НЕ отрывается от экрана, а лишь меняет направление.
 *  - В конце отправляется последний отрезок с willContinue = false: палец отпускается.
 *
 * Направление задаётся углом в градусах по часовой стрелке от «вверх»:
 *    0 = вверх, 90 = вправо, 180 = вниз, 270 = влево.
 *
 * Важно:
 *  - dispatchGesture отменяет ЛЮБОЙ другой жест, идущий в этот момент, кроме тех,
 *    что продолжают штрих. Поэтому нажатия, которые нужно сделать, пока джойстик
 *    зажат (например кнопка «огонь»), передаются параметрами tapX/tapY в
 *    hold/moveTo: тап добавляется В ТОТ ЖЕ жест вторым штрихом.
 *  - Если жест отменён (пользователь тронул экран, игра перехватила касание),
 *    сессия помечается как прерванная, остальные команды возвращают false.
 *
 * Пример:
 *   val joystick = JoystickExecutor()
 *   joystick.session(cx = 250f, cy = 850f, radius = 120f) {
 *       hold(angleDeg = 0f, holdMs = 3000L)                       // вперёд 3 секунды
 *       hold(angleDeg = 90f, holdMs = 1500L, tapX = 2000f, tapY = 900f) // вправо + тап «огонь»
 *       circle(turns = 1f, periodMs = 4000L)                      // круг по часовой
 *   }                                                              // палец отпускается сам
 */
class JoystickExecutor {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Открывает сессию джойстика и гарантированно отпускает палец в конце,
     * даже если корутина отменена или блок выбросил исключение.
     *
     * @return true, если вся сессия прошла без прерываний
     */
    suspend fun session(
        cx: Float,
        cy: Float,
        radius: Float,
        block: suspend Session.() -> Unit
    ): Boolean {
        val s = Session(cx, cy, radius)
        try {
            s.block()
        } finally {
            withContext(NonCancellable) { s.release() }
        }
        return !s.isBroken
    }

    /** Короткая форма: удерживать направление [holdMs] мс и отпустить. */
    suspend fun hold(
        cx: Float,
        cy: Float,
        radius: Float,
        angleDeg: Float,
        holdMs: Long,
        strength: Float = 1f
    ): Boolean = session(cx, cy, radius) {
        hold(angleDeg, holdMs, strength)
    }

    inner class Session internal constructor(
        private val centerX: Float,
        private val centerY: Float,
        private val radius: Float
    ) {
        private var lastStroke: GestureDescription.StrokeDescription? = null
        private var curX = centerX
        private var curY = centerY
        private var dither = 1f

        var isBroken: Boolean = false
            private set

        /**
         * Один отрезок движения: ведёт палец из текущей точки в точку,
         * соответствующую углу и силе (0..1), за [durationMs] мс.
         *
         * @param tapX, tapY необязательный тап вторым пальцем в этом же жесте
         */
        suspend fun moveTo(
            angleDeg: Float,
            strength: Float = 1f,
            durationMs: Long = 600L,
            tapX: Float? = null,
            tapY: Float? = null
        ): Boolean {
            if (isBroken) return false

            val rad = angleDeg.toDouble() * PI / 180.0
            val k = strength.coerceIn(0f, 1f)
            val targetX = (centerX + (sin(rad) * radius * k).toFloat()).coerceAtLeast(0f)
            val targetY = (centerY - (cos(rad) * radius * k).toFloat()).coerceAtLeast(0f)

            // Если точка не меняется, чуть «дрожим» на 1 пиксель, иначе путь нулевой длины.
            val same = abs(targetX - curX) < 1f && abs(targetY - curY) < 1f
            val endX = targetX
            val endY = if (same) {
                dither = -dither
                (targetY + dither).coerceAtLeast(0f)
            } else {
                targetY
            }

            val path = Path().apply {
                moveTo(curX, curY)
                lineTo(endX, endY)
            }
            val dur = durationMs.coerceIn(30L, 5000L)

            val prev = lastStroke
            val stroke = try {
                if (prev == null) {
                    GestureDescription.StrokeDescription(path, 0L, dur, true)
                } else {
                    prev.continueStroke(path, 0L, dur, true)
                }
            } catch (t: Throwable) {
                markBroken("не удалось создать штрих: ${t.message}")
                return false
            }

            val builder = GestureDescription.Builder().addStroke(stroke)
            if (tapX != null && tapY != null) {
                val tapPath = Path().apply {
                    moveTo(tapX, tapY)
                    lineTo(tapX, tapY + 1f)
                }
                builder.addStroke(GestureDescription.StrokeDescription(tapPath, 0L, 60L))
            }

            val gesture = try {
                builder.build()
            } catch (t: Throwable) {
                markBroken("не удалось собрать жест: ${t.message}")
                return false
            }

            val ok = dispatchAndWait(gesture, dur + 2000L)
            if (!ok) {
                markBroken("жест отменён системой или отклонён")
                return false
            }
            lastStroke = stroke
            curX = endX
            curY = endY
            return true
        }

        /**
         * Удерживать направление [holdMs] мс. Время режется на отрезки по [segmentMs] мс
         * (на отрезках одно и то же направление, палец не отрывается).
         * Первый отрезок плавно выводит палец из центра на нужное отклонение.
         */
        suspend fun hold(
            angleDeg: Float,
            holdMs: Long,
            strength: Float = 1f,
            segmentMs: Long = 600L,
            tapX: Float? = null,
            tapY: Float? = null
        ): Boolean {
            var left = holdMs
            var first = true
            while (left > 0L) {
                val d = minOf(segmentMs, left)
                // Тап делаем только в первом отрезке, чтобы не спамить «огонь» каждые 600 мс.
                val ok = if (first) {
                    moveTo(angleDeg, strength, d, tapX, tapY)
                } else {
                    moveTo(angleDeg, strength, d)
                }
                if (!ok) return false
                first = false
                left -= d
            }
            return true
        }

        /**
         * Круговое движение: [turns] оборотов (отрицательное значение = против часовой)
         * за [periodMs] мс на один оборот. Каждый шаг [stepMs] мс.
         */
        suspend fun circle(
            turns: Float,
            periodMs: Long,
            strength: Float = 1f,
            startAngleDeg: Float = 0f,
            stepMs: Long = 150L
        ): Boolean {
            if (periodMs <= 0L || stepMs <= 0L) return false
            val totalMs = (abs(turns) * periodMs).toLong()
            val dir = if (turns >= 0f) 1f else -1f
            var t = 0L
            while (t < totalMs) {
                val a = startAngleDeg + dir * 360f * (t.toFloat() / periodMs.toFloat())
                val ok = moveTo(a, strength, stepMs)
                if (!ok) return false
                t += stepMs
            }
            return true
        }

        /** Вернуть палец в центр и отпустить. Вызывается автоматически в конце session(). */
        suspend fun release() {
            val prev = lastStroke ?: return
            if (isBroken) {
                lastStroke = null
                return
            }
            try {
                val path = Path().apply {
                    moveTo(curX, curY)
                    lineTo(curX, curY + 1f)
                }
                val stroke = prev.continueStroke(path, 0L, 40L, false)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                dispatchAndWait(gesture, 2000L)
            } catch (t: Throwable) {
                EventLogManager.log(
                    EventLogManager.TAG_GESTURE,
                    "JOYSTICK: ошибка отпускания: ${t.message}",
                    isError = true
                )
            }
            lastStroke = null
        }

        private fun markBroken(reason: String) {
            isBroken = true
            EventLogManager.log(
                EventLogManager.TAG_GESTURE,
                "JOYSTICK: сессия прервана: $reason",
                isError = true
            )
        }
    }

    private suspend fun dispatchAndWait(gesture: GestureDescription, timeoutMs: Long): Boolean {
        val service = AccessibilityServiceHolder.service.value
        if (service == null) {
            EventLogManager.log(
                EventLogManager.TAG_GESTURE,
                "JOYSTICK: AccessibilityService недоступен",
                isError = true
            )
            return false
        }
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }
                val dispatched = try {
                    service.dispatchGesture(gesture, callback, mainHandler)
                } catch (t: Throwable) {
                    false
                }
                if (!dispatched && cont.isActive) cont.resume(false)
            }
        }
        return result ?: false
    }
}
