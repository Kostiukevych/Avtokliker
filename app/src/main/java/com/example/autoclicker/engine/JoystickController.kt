package com.example.autoclicker.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.data.JoystickRepository
import com.example.autoclicker.data.JoystickSettings
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.data.StickConfig
import com.example.autoclicker.service.AccessibilityServiceHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Одновременные джойстики J1/J2 + кнопка B.
 * Сегменты по SEGMENT_MS мс; удерживаемые штрихи продолжаются через continueStroke.
 */
class JoystickController private constructor(
    private val gestureExecutor: GestureExecutor,
    private val settingsRepository: SettingsRepository
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var watchJob: Job? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private data class FingerState(
        var stroke: GestureDescription.StrokeDescription? = null,
        var x: Float = 0f,
        var y: Float = 0f,
        var active: Boolean = false,
        var circleAngle: Float = 0f,
        var first: Boolean = true
    )

    /**
     * Один шаг сценария автомимикрии.
     * angle1/angle2 — градусы для J1/J2 (null = палец отпущен / неактивен в шаге).
     * strengthPct — сила 30..100; circle = true → движение по дуге.
     */
    private data class MimicStep(
        val durationMs: Long,
        val angle1: Int? = 0,
        val angle2: Int? = null,
        val strengthPct: Int = 85,
        val circle1: Boolean = false,
        val circle2: Boolean = false
    )

    /** Сценарий на всю катку: циклы пока не «Продолжить». Разнообразное движение. */
    private val mimicScenario: List<MimicStep> = listOf(
        // Разгон вперёд
        MimicStep(3500, angle1 = 0, angle2 = 0, strengthPct = 90),
        MimicStep(2000, angle1 = 0, angle2 = 45, strengthPct = 80),
        MimicStep(2000, angle1 = 0, angle2 = 315, strengthPct = 80),
        // Стрейф влево / вправо
        MimicStep(2500, angle1 = 270, angle2 = 270, strengthPct = 75),
        MimicStep(2500, angle1 = 90, angle2 = 90, strengthPct = 75),
        // Диагонали
        MimicStep(3000, angle1 = 315, angle2 = 45, strengthPct = 85),
        MimicStep(3000, angle1 = 45, angle2 = 315, strengthPct = 85),
        // Круг J1 + удержание J2
        MimicStep(5000, angle1 = 0, angle2 = 0, strengthPct = 70, circle1 = true),
        // Назад / укрытие
        MimicStep(2000, angle1 = 180, angle2 = 180, strengthPct = 60),
        MimicStep(1500, angle1 = null, angle2 = null, strengthPct = 50), // короткая пауза
        // Вперёд + обзор J2
        MimicStep(4000, angle1 = 0, angle2 = 90, strengthPct = 90),
        MimicStep(4000, angle1 = 0, angle2 = 270, strengthPct = 90),
        // Зигзаг
        MimicStep(1500, angle1 = 40, angle2 = 40, strengthPct = 95),
        MimicStep(1500, angle1 = 320, angle2 = 320, strengthPct = 95),
        MimicStep(1500, angle1 = 40, angle2 = 40, strengthPct = 95),
        MimicStep(1500, angle1 = 320, angle2 = 320, strengthPct = 95),
        // Длинный бег
        MimicStep(6000, angle1 = 0, angle2 = 0, strengthPct = 100),
        // Круг обоими
        MimicStep(6000, angle1 = 0, angle2 = 180, strengthPct = 65, circle1 = true, circle2 = true),
        MimicStep(2000, angle1 = 90, angle2 = 270, strengthPct = 80),
        MimicStep(2000, angle1 = 270, angle2 = 90, strengthPct = 80)
    )

    private var mimicStepIndex = 0
    private var mimicStepEndsAt = 0L
    private var lastLoggedPhase: JoystickMatchPhase.Phase? = null

    private val f1 = FingerState()
    private val f2 = FingerState()
    private val fB = FingerState()
    private var dither = 1f
    private var lastFireTapMs = 0L
    private var pendingTaps = ArrayDeque<Pair<Float, Float>>()
    private var pendingTapWaiters = ArrayDeque<kotlinx.coroutines.CompletableDeferred<Boolean>>()
    private var consecutiveFails = 0
    private var paused = false

    fun start(): Boolean {
        if (_isRunning.value) return true
        val ctx = AccessibilityServiceHolder.service.value?.applicationContext
            ?: return false.also {
                EventLogManager.log(EventLogManager.TAG_GESTURE, "JOYSTICK: нет accessibility", isError = true)
            }
        val joyRepo = JoystickRepository.getInstance(ctx)
        val s = joyRepo.getLatest()
        if (!s.masterEnabled || !s.hasAnyActive) return false

        resetFingers(s, ctx)
        consecutiveFails = 0
        paused = false
        mimicStepIndex = 0
        mimicStepEndsAt = 0L
        JoystickMatchPhase.reset()
        lastLoggedPhase = null
        _isRunning.value = true
        EventLogManager.log(
            EventLogManager.TAG_GESTURE,
            "JOYSTICK: старт J1=${s.stick1.enabled && s.stick1.isConfigured} " +
                "(${s.stick1.x.toInt()},${s.stick1.y.toInt()}) " +
                "J2=${s.stick2.enabled && s.stick2.isConfigured} " +
                "B=${s.button.enabled && s.button.isConfigured}" +
                if (s.autoMimicEnabled) " автомимикрия=ВКЛ" else ""
        )
        loopJob = scope.launch { runLoop(joyRepo) }
        val smart = SmartEngine.getInstance(ctx, gestureExecutor, settingsRepository)
        watchJob = scope.launch {
            smart.status.collect { st ->
                if (st == CycleStatus.STOPPED && _isRunning.value) {
                    stop("умный режим остановлен")
                }
            }
        }
        return true
    }

    fun stop(reason: String = "стоп") {
        if (!_isRunning.value && loopJob == null) return
        _isRunning.value = false
        loopJob?.cancel()
        loopJob = null
        watchJob?.cancel()
        watchJob = null
        scope.launch {
            withContext(NonCancellable) { releaseAll() }
        }
        EventLogManager.log(EventLogManager.TAG_GESTURE, "JOYSTICK: остановка: $reason")
    }

    suspend fun pause() {
        paused = true
        withContext(NonCancellable) { releaseAll() }
        delay(50)
    }

    fun resume() {
        paused = false
        f1.first = true
        f2.first = true
        fB.first = true
        f1.stroke = null
        f2.stroke = null
        fB.stroke = null
    }

    suspend fun <T> withPaused(block: suspend () -> T): T {
        val was = _isRunning.value
        if (was) pause()
        return try {
            block()
        } finally {
            if (was) resume()
        }
    }

    suspend fun enqueueTap(x: Float, y: Float): Boolean {
        if (!_isRunning.value || paused) return false
        val def = kotlinx.coroutines.CompletableDeferred<Boolean>()
        synchronized(pendingTaps) {
            pendingTaps.addLast(x to y)
            pendingTapWaiters.addLast(def)
        }
        return withTimeoutOrNull(SEGMENT_MS + 1500L) { def.await() } ?: false
    }

    suspend fun test(kind: Int) {
        val ctx = AccessibilityServiceHolder.service.value?.applicationContext ?: return
        val s = JoystickRepository.getInstance(ctx).getLatest()
        val stick = if (kind == 1) s.stick1 else s.stick2
        if (!stick.isConfigured) return
        val exec = JoystickExecutor()
        exec.hold(
            stick.x, stick.y, stick.radiusPx.toFloat(),
            stick.angleDeg.toFloat(), 3000L, stick.strength / 100f
        )
    }

    private fun resetFingers(s: JoystickSettings, ctx: android.content.Context) {
        val dm = DisplayMetrics()
        try {
            val wm = ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
        } catch (_: Exception) {
        }
        var sw = s.screenW
        var sh = s.screenH
        var sx = 1f
        var sy = 1f
        if (sw > 0 && sh > 0 && dm.widthPixels > 0 &&
            (dm.widthPixels != sw || dm.heightPixels != sh)
        ) {
            sx = dm.widthPixels.toFloat() / sw
            sy = dm.heightPixels.toFloat() / sh
            EventLogManager.log(
                EventLogManager.TAG_GESTURE,
                "JOYSTICK: экран изменился ${sw}x$sh → ${dm.widthPixels}x${dm.heightPixels}, координаты пересчитаны"
            )
        }
        fun mapStick(st: StickConfig): Pair<Float, Float> =
            (st.x * sx) to (st.y * sy)

        if (s.stick1.enabled && s.stick1.isConfigured) {
            val (x, y) = mapStick(s.stick1)
            f1.x = x; f1.y = y; f1.active = true; f1.first = true; f1.stroke = null; f1.circleAngle = s.stick1.angleDeg.toFloat()
        } else f1.active = false
        if (s.stick2.enabled && s.stick2.isConfigured) {
            val (x, y) = mapStick(s.stick2)
            f2.x = x; f2.y = y; f2.active = true; f2.first = true; f2.stroke = null; f2.circleAngle = s.stick2.angleDeg.toFloat()
        } else f2.active = false
        if (s.button.enabled && s.button.isConfigured) {
            fB.x = s.button.x * sx; fB.y = s.button.y * sy
            fB.active = s.button.mode == "hold"
            fB.first = true; fB.stroke = null
        } else fB.active = false
    }

    private suspend fun runLoop(joyRepo: JoystickRepository) {
        while (scope.isActive && _isRunning.value) {
            if (paused) {
                delay(100)
                continue
            }
            val s = joyRepo.getLatest()
            if (!s.masterEnabled || !s.hasAnyActive) {
                stop("джойстики выключены")
                break
            }
            val ok = runSegment(s)
            if (ok) {
                consecutiveFails = 0
            } else {
                consecutiveFails++
                EventLogManager.log(
                    EventLogManager.TAG_GESTURE,
                    "JOYSTICK: жест отменён, перезапуск",
                    isError = true
                )
                f1.stroke = null; f2.stroke = null; fB.stroke = null
                f1.first = true; f2.first = true; fB.first = true
                delay(300)
                if (consecutiveFails >= 20) {
                    stop("слишком много отмен жестов подряд")
                    break
                }
            }
        }
    }

    private fun stickTarget(st: StickConfig, angle: Float, scaleR: Float): Pair<Float, Float> {
        val rad = angle * PI / 180.0
        val k = (st.strength / 100f).coerceIn(0.3f, 1f)
        val r = st.radiusPx * scaleR
        val tx = (st.x + (sin(rad) * r * k).toFloat()).coerceAtLeast(0f)
        val ty = (st.y - (cos(rad) * r * k).toFloat()).coerceAtLeast(0f)
        return tx to ty
    }


    private fun applyMimicStep(s: JoystickSettings, step: MimicStep) {
        // логируем только крупные смены (не каждый сегмент 500мс)
        EventLogManager.log(
            EventLogManager.TAG_GESTURE,
            "JOYSTICK: сценарий шаг ${mimicStepIndex + 1}/${mimicScenario.size} " +
                "J1=${step.angle1 ?: "—"} J2=${step.angle2 ?: "—"} ${step.durationMs}мс"
        )
        f1.first = true
        f2.first = true
        if (step.angle1 != null) f1.circleAngle = step.angle1.toFloat()
        if (step.angle2 != null) f2.circleAngle = step.angle2.toFloat()
    }

    private suspend fun runSegment(s: JoystickSettings): Boolean {
        val maxStrokes = try {
            GestureDescription.getMaxStrokeCount()
        } catch (_: Exception) {
            10
        }
        val builder = GestureDescription.Builder()
        var strokes = 0
        val dur = SEGMENT_MS

        // --- Автомимикрия: фаза катки + шаг сценария ---
        var stick1Override: StickConfig? = null
        var stick2Override: StickConfig? = null
        var forceCircle1 = false
        var forceCircle2 = false
        var releaseBoth = false

        if (s.autoMimicEnabled) {
            val phase = JoystickMatchPhase.phase
            if (phase != lastLoggedPhase) {
                val prev = lastLoggedPhase
                lastLoggedPhase = phase
                EventLogManager.log(
                    EventLogManager.TAG_GESTURE,
                    when (phase) {
                        JoystickMatchPhase.Phase.IN_MATCH ->
                            "JOYSTICK: автомимикрия — в катке, сценарий с начала"
                        JoystickMatchPhase.Phase.BETWEEN_MATCHES ->
                            "JOYSTICK: автомимикрия — пауза до «Начать» / следующей катки"
                    }
                )
                if (phase == JoystickMatchPhase.Phase.IN_MATCH && prev == JoystickMatchPhase.Phase.BETWEEN_MATCHES) {
                    mimicStepIndex = 0
                    mimicStepEndsAt = 0L
                    f1.first = true
                    f2.first = true
                    f1.stroke = null
                    f2.stroke = null
                }
            }
            if (phase == JoystickMatchPhase.Phase.BETWEEN_MATCHES) {
                releaseBoth = true
                f1.active = false
                f2.active = false
            } else {
                val now = System.currentTimeMillis()
                if (mimicStepEndsAt == 0L || now >= mimicStepEndsAt) {
                    if (mimicStepEndsAt != 0L) {
                        mimicStepIndex = (mimicStepIndex + 1) % mimicScenario.size
                    }
                    val step = mimicScenario[mimicStepIndex]
                    mimicStepEndsAt = now + step.durationMs
                    applyMimicStep(s, step)
                }
                val cur = mimicScenario[mimicStepIndex]
                if (cur.angle1 == null && cur.angle2 == null) {
                    releaseBoth = true
                    f1.active = false
                    f2.active = false
                } else {
                    if (s.stick1.enabled && s.stick1.isConfigured && cur.angle1 != null) {
                        stick1Override = s.stick1.copy(
                            mode = if (cur.circle1) "circle" else "hold",
                            angleDeg = cur.angle1,
                            strength = cur.strengthPct
                        )
                        forceCircle1 = cur.circle1
                        f1.active = true
                    }
                    if (s.stick2.enabled && s.stick2.isConfigured && cur.angle2 != null) {
                        stick2Override = s.stick2.copy(
                            mode = if (cur.circle2) "circle" else "hold",
                            angleDeg = cur.angle2,
                            strength = cur.strengthPct
                        )
                        forceCircle2 = cur.circle2
                        f2.active = true
                    } else if (cur.angle2 == null) {
                        f2.active = false
                    }
                }
            }
        }

        fun addHold(finger: FingerState, st: StickConfig?, isCircle: Boolean) {
            if (!finger.active || st == null || !st.enabled || !st.isConfigured) return
            if (strokes >= maxStrokes) return
            val scaleR = 1f
            val (tx, ty) = if (isCircle) {
                finger.circleAngle += 360f * SEGMENT_MS / (st.circlePeriodSec * 1000f)
                stickTarget(st, finger.circleAngle, scaleR)
            } else {
                stickTarget(st, st.angleDeg.toFloat(), scaleR)
            }
            val path = Path()
            if (finger.first || finger.stroke == null) {
                path.moveTo(st.x, st.y)
                if (isCircle) {
                    val steps = 4
                    for (i in 1..steps) {
                        val a = finger.circleAngle - 360f * SEGMENT_MS / (st.circlePeriodSec * 1000f) * (1f - i.toFloat() / steps)
                        val (px, py) = stickTarget(st, a, scaleR)
                        path.lineTo(px, py)
                    }
                } else {
                    path.lineTo(tx, ty)
                }
            } else {
                path.moveTo(finger.x, finger.y)
                if (isCircle) {
                    val steps = 4
                    for (i in 1..steps) {
                        val a = finger.circleAngle - 360f * SEGMENT_MS / (st.circlePeriodSec * 1000f) * (1f - i.toFloat() / steps)
                        val (px, py) = stickTarget(st, a, scaleR)
                        path.lineTo(px, py)
                    }
                } else {
                    dither = -dither
                    path.lineTo(tx, (ty + dither).coerceAtLeast(0f))
                }
            }
            val stroke = try {
                val prev = finger.stroke
                if (prev == null || finger.first) {
                    GestureDescription.StrokeDescription(path, 0L, dur, true)
                } else {
                    prev.continueStroke(path, 0L, dur, true)
                }
            } catch (_: Throwable) {
                null
            } ?: return
            try {
                builder.addStroke(stroke)
                finger.stroke = stroke
                finger.x = tx
                finger.y = ty
                finger.first = false
                strokes++
            } catch (_: Throwable) {
            }
        }

        if (releaseBoth) {
            // нет удерживаемых пальцев в этом сегменте
        } else {
            val st1 = stick1Override ?: s.stick1
            val st2 = stick2Override ?: s.stick2
            val c1 = if (s.autoMimicEnabled) forceCircle1 else (st1.mode == "circle")
            val c2 = if (s.autoMimicEnabled) forceCircle2 else (st2.mode == "circle")
            addHold(f1, st1, c1)
            addHold(f2, st2, c2)
        }
        if (s.button.enabled && s.button.isConfigured && s.button.mode == "hold") {
            fB.active = true
            if (strokes < maxStrokes) {
                val path = Path().apply {
                    moveTo(fB.x, fB.y)
                    dither = -dither
                    lineTo(fB.x, (fB.y + dither).coerceAtLeast(0f))
                }
                try {
                    val prev = fB.stroke
                    val stroke = if (prev == null || fB.first) {
                        GestureDescription.StrokeDescription(path, 0L, dur, true)
                    } else prev.continueStroke(path, 0L, dur, true)
                    builder.addStroke(stroke)
                    fB.stroke = stroke
                    fB.first = false
                    strokes++
                } catch (_: Throwable) {
                }
            }
        }

        // Fire button taps
        if (s.button.enabled && s.button.isConfigured && s.button.mode == "tap") {
            val now = System.currentTimeMillis()
            if (now - lastFireTapMs >= s.button.intervalMs && strokes < maxStrokes) {
                try {
                    val path = Path().apply {
                        moveTo(s.button.x, s.button.y)
                        lineTo(s.button.x, s.button.y + 1f)
                    }
                    builder.addStroke(GestureDescription.StrokeDescription(path, 0L, 60L, false))
                    strokes++
                    lastFireTapMs = now
                } catch (_: Throwable) {
                }
            }
        }

        // Smart mode taps
        val tapsThis = mutableListOf<Pair<Float, Float>>()
        synchronized(pendingTaps) {
            while (pendingTaps.isNotEmpty() && strokes < maxStrokes) {
                tapsThis.add(pendingTaps.removeFirst())
                strokes++
            }
        }
        for ((tx, ty) in tapsThis) {
            try {
                val path = Path().apply {
                    moveTo(tx, ty)
                    lineTo(tx, ty + 1f)
                }
                builder.addStroke(GestureDescription.StrokeDescription(path, 0L, 60L, false))
            } catch (_: Throwable) {
            }
        }

        if (strokes == 0) {
            delay(SEGMENT_MS)
            return true
        }

        val gesture = try {
            builder.build()
        } catch (t: Throwable) {
            EventLogManager.log(EventLogManager.TAG_GESTURE, "JOYSTICK: build error ${t.message}", isError = true)
            completeTaps(tapsThis.size, false)
            return false
        }

        val ok = dispatchAndWait(gesture, SEGMENT_MS + 2000L)
        completeTaps(tapsThis.size, ok)
        return ok
    }

    private fun completeTaps(count: Int, ok: Boolean) {
        synchronized(pendingTaps) {
            repeat(count) {
                if (pendingTapWaiters.isNotEmpty()) {
                    pendingTapWaiters.removeFirst().complete(ok)
                }
            }
        }
    }

    private suspend fun releaseAll() {
        val builder = GestureDescription.Builder()
        var any = false
        fun releaseFinger(f: FingerState) {
            val prev = f.stroke ?: return
            try {
                val path = Path().apply {
                    moveTo(f.x, f.y)
                    lineTo(f.x, f.y + 1f)
                }
                builder.addStroke(prev.continueStroke(path, 0L, 40L, false))
                any = true
            } catch (_: Throwable) {
            }
            f.stroke = null
            f.first = true
        }
        releaseFinger(f1)
        releaseFinger(f2)
        releaseFinger(fB)
        if (!any) return
        try {
            val g = builder.build()
            dispatchAndWait(g, 2000L)
        } catch (_: Throwable) {
        }
    }

    private suspend fun dispatchAndWait(gesture: GestureDescription, timeoutMs: Long): Boolean {
        val service = AccessibilityServiceHolder.service.value ?: return false
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
                } catch (_: Throwable) {
                    false
                }
                if (!dispatched && cont.isActive) cont.resume(false)
            }
        }
        return result ?: false
    }

    companion object {
        private const val SEGMENT_MS = 500L
        @Volatile private var INSTANCE: JoystickController? = null
        fun getInstance(
            gestureExecutor: GestureExecutor,
            settingsRepository: SettingsRepository
        ): JoystickController {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: JoystickController(gestureExecutor, settingsRepository).also { INSTANCE = it }
            }
        }

        fun isActive(): Boolean = INSTANCE?._isRunning?.value == true

        fun activeOrNull(): JoystickController? = INSTANCE?.takeIf { it._isRunning.value }
    }
}
