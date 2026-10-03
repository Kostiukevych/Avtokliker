package com.example.autoclicker.engine

import com.example.autoclicker.data.ClickPoint
import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.service.AccessibilityServiceHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Состояния цикла автоматизации.
 */
enum class CycleStatus {
    STOPPED,
    RUNNING,
    WAITING_CYCLE
}

/**
 * Контроллер цикла автоматизации.
 *
 * Логика работы:
 * 1. Нажатие START.
 * 2. Обратный отсчёт 2 секунды (2s, 1s).
 * 3. Выполняется ТОЛЬКО Point 1 (Point 2 и Point 3 НЕ нажимаются).
 * 4. Запускается таймер цикла на N минут (1..15 мин) с обратным отсчетом MM:SS.
 * 5. По истечении таймера: Point 1 -> пауза 1с -> Point 2 -> пауза 1с -> Point 3.
 * 6. Снова таймер N минут, снова P1 -> P2 -> P3, до нажатия STOP.
 */
class CycleController private constructor(
    private val gestureExecutor: GestureExecutor,
    private val settingsRepository: SettingsRepository
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cycleMutex = Mutex()
    private var cycleJob: Job? = null

    /**
     * Хуки жизненного цикла нажатия (для оверлея и анимаций).
     */
    var tapHooks: TapHooks? = null

    private val _status = MutableStateFlow(CycleStatus.STOPPED)
    val status: StateFlow<CycleStatus> = _status.asStateFlow()

    private val _currentAction = MutableStateFlow("Остановлен")
    val currentAction: StateFlow<String> = _currentAction.asStateFlow()

    private val _lastAction = MutableStateFlow("—")
    val lastAction: StateFlow<String> = _lastAction.asStateFlow()

    private val _nextAction = MutableStateFlow("Готов к запуску")
    val nextAction: StateFlow<String> = _nextAction.asStateFlow()

    private val _remainingSeconds = MutableStateFlow(0)
    val remainingSeconds: StateFlow<Int> = _remainingSeconds.asStateFlow()

    private val _countdownText = MutableStateFlow("")
    val countdownText: StateFlow<String> = _countdownText.asStateFlow()

    private val _cycleNumber = MutableStateFlow(0)
    val cycleNumber: StateFlow<Int> = _cycleNumber.asStateFlow()

    val isRunning: Boolean
        get() = _status.value != CycleStatus.STOPPED && cycleJob?.isActive == true

    /**
     * Запуск автоматизации.
     */
    fun start(): Boolean {
        if (!AccessibilityServiceHolder.isConnected) {
            EventLogManager.log(
                EventLogManager.TAG_CYCLE,
                "ERROR: AccessibilityService unavailable. Запуск невозможен.",
                isError = true
            )
            return false
        }

        coroutineScope.launch {
            cycleMutex.withLock {
                if (cycleJob?.isActive == true) {
                    EventLogManager.log(
                        EventLogManager.TAG_CYCLE,
                        "Цикл уже выполняется. Повторный START проигнорирован."
                    )
                    return@withLock
                }

                _cycleNumber.value = 1
                _status.value = CycleStatus.RUNNING
                _lastAction.value = "START"
                _currentAction.value = "Старт: Point 1 через 2с"
                _nextAction.value = "Point 1"
                _remainingSeconds.value = 2
                _countdownText.value = "2s"

                // Проверка ориентации и размера экрана при калибровке
                val latestSettings = settingsRepository.getLatestSettings()
                val context = settingsRepository.context
                val currentOrientation = context.resources.configuration.orientation
                if (latestSettings.pointsOrientation != 0 && latestSettings.pointsOrientation != currentOrientation) {
                    val warn = "Точки заданы для другой ориентации экрана"
                    EventLogManager.log(EventLogManager.TAG_CYCLE, "WARN: $warn", isError = true)
                    _currentAction.value = "Предупреждение: $warn"
                }

                val delayMin = latestSettings.cycleDelayMinutes
                EventLogManager.log(
                    EventLogManager.TAG_CYCLE,
                    "CYCLE STARTED: Первый клик Point 1 через 2с, таймер повторов = $delayMin мин"
                )

                val newJob = launch {
                    val currentJob = coroutineContext[Job]
                    try {
                        runLoop()
                    } catch (e: CancellationException) {
                        EventLogManager.log(EventLogManager.TAG_CYCLE, "CYCLE CANCELLED (Корректно остановлен)")
                        throw e
                    } catch (t: Throwable) {
                        EventLogManager.log(
                            EventLogManager.TAG_CYCLE,
                            "ERROR in cycle: ${t.message}",
                            isError = true
                        )
                    } finally {
                        cycleMutex.withLock {
                            if (cycleJob === currentJob) {
                                cycleJob = null
                                resetState()
                            }
                        }
                    }
                }
                cycleJob = newJob
            }
        }
        return true
    }

    /**
     * Немедленная остановка цикла с ожиданием завершения отменяемой корутины.
     */
    fun stop() {
        coroutineScope.launch {
            cycleMutex.withLock {
                val jobToCancel = cycleJob
                cycleJob = null
                jobToCancel?.cancelAndJoin()
                resetState()
                _lastAction.value = "STOP"
                EventLogManager.log(EventLogManager.TAG_CYCLE, "CYCLE STOPPED: Полная остановка")
            }
        }
    }

    private fun resetState() {
        _status.value = CycleStatus.STOPPED
        _currentAction.value = "Остановлен"
        _nextAction.value = "Готов к запуску"
        _remainingSeconds.value = 0
        _countdownText.value = ""
    }

    /**
     * Одиночное тестовое нажатие по точке с вызовом единых хуков.
     */
    fun testClick(pointId: Int) {
        coroutineScope.launch {
            val point = settingsRepository.getLatestSettings().getPointById(pointId)
            if (!point.isConfigured) {
                EventLogManager.log(
                    EventLogManager.TAG_CYCLE,
                    "POINT NOT CALIBRATED (Point $pointId)",
                    isError = true
                )
                _currentAction.value = "Тест: Point $pointId не настроена"
                return@launch
            }
            EventLogManager.log(
                EventLogManager.TAG_CYCLE,
                "TEST TAP POINT $pointId (${point.x.toInt()}, ${point.y.toInt()})"
            )
            _currentAction.value = "Тест Point $pointId..."
            val success = performTapWithHooks(point.id, point.x, point.y)
            if (!success) {
                EventLogManager.log(
                    EventLogManager.TAG_CYCLE,
                    "ERROR: Test tap failed for point $pointId",
                    isError = true
                )
                _currentAction.value = "Ошибка теста Point $pointId"
            } else {
                _lastAction.value = "Тест: Point $pointId OK"
                _currentAction.value = "Тест Point $pointId OK"
            }
        }
    }

    /**
     * Вызов единых хуков TapHooks до и после нажатия.
     */
    private suspend fun performTapWithHooks(
        pointId: Int,
        x: Float,
        y: Float,
        tapIndex: Int = 1,
        totalTaps: Int = 1
    ): Boolean {
        tapHooks?.beforeTap(pointId, x, y, tapIndex, totalTaps)
        val success = try {
            gestureExecutor.performTap(x, y)
        } catch (t: Throwable) {
            EventLogManager.log(
                EventLogManager.TAG_CYCLE,
                "ERROR: Ошибка жеста: ${t.message}",
                isError = true
            )
            false
        }
        tapHooks?.afterTap(pointId, x, y, success, tapIndex, totalTaps)
        return success
    }

    /**
     * Основной цикл автоматизации.
     */
    private suspend fun runLoop() {
        var isFirstRun = true

        while (true) {
            coroutineContext.ensureActive()

            if (isFirstRun) {
                // 1. Первый запуск: 2 секунды обратного отсчета
                _status.value = CycleStatus.RUNNING
                _nextAction.value = "Point 1"

                _currentAction.value = "Старт: Point 1 через 2с"
                _countdownText.value = "2s"
                _remainingSeconds.value = 2
                delay(1000L)
                coroutineContext.ensureActive()

                _currentAction.value = "Старт: Point 1 через 1с"
                _countdownText.value = "1s"
                _remainingSeconds.value = 1
                delay(1000L)
                coroutineContext.ensureActive()

                _countdownText.value = ""
                _remainingSeconds.value = 0

                // 2. При первом запуске нажимается ТОЛЬКО Point 1!
                // Point 2 и Point 3 в этот момент НЕ нажимаются.
                val initialSettings = settingsRepository.getLatestSettings()
                if (initialSettings.point1.enabled) {
                    _status.value = CycleStatus.RUNNING
                    _nextAction.value = "Point 1"
                    executePoint(initialSettings.point1)
                }

                isFirstRun = false
            } else {
                // В последующих циклах выполняются по очереди: Point 1 -> пауза 1с -> Point 2 -> пауза 1с -> Point 3
                val currentCycle = _cycleNumber.value

                // 1. Point 1
                val s1 = settingsRepository.getLatestSettings()
                if (s1.point1.enabled) {
                    _status.value = CycleStatus.RUNNING
                    _nextAction.value = findNextEnabledPointLabel(s1, afterPointId = 1)
                    executePoint(s1.point1)
                    safeDelay(1000L, "Пауза 1с после Point 1")
                }

                // 2. Point 2
                coroutineContext.ensureActive()
                val s2 = settingsRepository.getLatestSettings()
                if (s2.point2.enabled) {
                    _status.value = CycleStatus.RUNNING
                    _nextAction.value = findNextEnabledPointLabel(s2, afterPointId = 2)
                    executePoint(s2.point2)
                    safeDelay(1000L, "Пауза 1с после Point 2")
                }

                // 3. Point 3
                coroutineContext.ensureActive()
                val s3 = settingsRepository.getLatestSettings()
                if (s3.point3.enabled) {
                    _status.value = CycleStatus.RUNNING
                    _nextAction.value = findNextEnabledPointLabel(s3, afterPointId = 3)
                    executePoint(s3.point3)
                    _lastAction.value = "Point 3 (Цикл #$currentCycle)"
                    EventLogManager.log(
                        EventLogManager.TAG_CYCLE,
                        "POINT 3 COMPLETED: Запуск матча выполнен (Цикл #$currentCycle)"
                    )
                    val hasMore = (4..10).any { s3.getPointById(it).enabled }
                    if (hasMore) {
                        safeDelay(1000L, "Пауза 1с после Point 3")
                    }
                }

                // 4..10. Точки 4..10
                for (id in 4..10) {
                    coroutineContext.ensureActive()
                    val sExtra = settingsRepository.getLatestSettings()
                    val pt = sExtra.getPointById(id)
                    if (pt.enabled) {
                        _status.value = CycleStatus.RUNNING
                        _nextAction.value = findNextEnabledPointLabel(sExtra, afterPointId = id)
                        executePoint(pt)
                        _lastAction.value = "Point $id (Цикл #$currentCycle)"
                        EventLogManager.log(
                            EventLogManager.TAG_CYCLE,
                            "POINT $id COMPLETED (Цикл #$currentCycle)"
                        )
                        val hasMore = ((id + 1)..10).any { sExtra.getPointById(it).enabled }
                        if (hasMore) {
                            safeDelay(1000L, "Пауза 1с после Point $id")
                        }
                    }
                }

                _cycleNumber.value = currentCycle + 1
            }

            // 3. Запуск таймера ожидания цикла на N минут (свежие настройки)
            coroutineContext.ensureActive()
            val freshSettings = settingsRepository.getLatestSettings()
            val delayMin = freshSettings.cycleDelayMinutes
            waitCycleInterval(delayMin)
        }
    }

    /**
     * Отсчет фиксированного времени с обновлением таймера MM:SS каждую секунду.
     */
    private suspend fun waitCycleInterval(delayMinutes: Int) {
        _status.value = CycleStatus.WAITING_CYCLE
        val totalSeconds = delayMinutes.coerceIn(1, 15) * 60

        EventLogManager.log(
            EventLogManager.TAG_CYCLE,
            String.format(Locale.getDefault(), "TIMER STARTED: Ожидание %02d:00 до выполнения кликов", delayMinutes)
        )

        var remaining = totalSeconds
        while (remaining > 0) {
            coroutineContext.ensureActive()

            val min = remaining / 60
            val sec = remaining % 60
            val formatted = String.format(Locale.getDefault(), "%02d:%02d", min, sec)

            _remainingSeconds.value = remaining
            _countdownText.value = formatted
            _currentAction.value = "Ждём таймер: P1→P2→P3 через $formatted"
            _nextAction.value = "P1→P2→P3 через $formatted"

            delay(1000L)
            remaining--
        }

        _remainingSeconds.value = 0
        _countdownText.value = ""
        _status.value = CycleStatus.RUNNING
    }

    /**
     * Выполнение нажатий для конкретной точки с защитой от сбоев.
     * Ненастроенная точка пропускается с записью в лог и предупреждением, не останавливая цикл.
     */
    private suspend fun executePoint(point: ClickPoint) {
        if (!point.isConfigured) {
            EventLogManager.log(
                EventLogManager.TAG_CYCLE,
                "SKIP: Точка ${point.id} не настроена. Пропуск.",
                isError = true
            )
            _currentAction.value = "Пропуск Point ${point.id}: не настроена"
            return
        }

        val count = point.clickCount.coerceIn(1, 10)

        for (i in 1..count) {
            coroutineContext.ensureActive()

            val actionDesc = "Point ${point.id}, нажатие $i/$count"
            _currentAction.value = actionDesc
            EventLogManager.log(EventLogManager.TAG_CYCLE, actionDesc)

            val freshPoint = settingsRepository.getLatestSettings().getPointById(point.id)
            val success = performTapWithHooks(freshPoint.id, freshPoint.x, freshPoint.y, tapIndex = i, totalTaps = count)
            if (success) {
                _lastAction.value = actionDesc
            } else {
                EventLogManager.log(
                    EventLogManager.TAG_CYCLE,
                    "WARN: Жест не выполнен для Point ${point.id} ($i/$count)",
                    isError = true
                )
            }

            if (i < count) {
                var remaining = point.intervalSec.coerceIn(1, 30)
                while (remaining > 0) {
                    coroutineContext.ensureActive()
                    _remainingSeconds.value = remaining
                    _countdownText.value = "${remaining}s"
                    _currentAction.value = "Point ${point.id}: пауза ${remaining}с ($i/$count)"
                    delay(1000L)
                    remaining--
                }
                _remainingSeconds.value = 0
                _countdownText.value = ""
            }
        }
    }

    private suspend fun safeDelay(ms: Long, actionDescription: String) {
        _currentAction.value = actionDescription
        delay(ms)
    }

    private fun findNextEnabledPointLabel(settings: com.example.autoclicker.data.ClickerSettings, afterPointId: Int): String {
        for (id in (afterPointId + 1)..10) {
            if (settings.getPointById(id).enabled) return "Point $id"
        }
        return "Ожидание таймера"
    }

    companion object {
        @Volatile
        private var INSTANCE: CycleController? = null

        fun getInstance(
            gestureExecutor: GestureExecutor,
            settingsRepository: SettingsRepository
        ): CycleController {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CycleController(gestureExecutor, settingsRepository).also {
                    INSTANCE = it
                }
            }
        }
    }
}
