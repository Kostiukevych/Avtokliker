package com.example.autoclicker.engine

import com.example.autoclicker.data.ClickPoint
import com.example.autoclicker.data.ClickerSettings
import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.service.AccessibilityServiceHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * Работает строго по независимому фиксированному таймеру:
 * START -> отсчет интервала (например, 7 мин) -> POINT 1 -> POINT 2 -> POINT 3 -> новый интервал 7 мин -> ...
 * Не анализирует продолжительность матча и не ждет окончания игры.
 */
class CycleController private constructor(
    private val gestureExecutor: GestureExecutor,
    private val settingsRepository: SettingsRepository
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cycleMutex = Mutex()
    private var cycleJob: Job? = null

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
     * Запуск автоматизации по фиксированному таймеру.
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
                _currentAction.value = "Запуск первого интервала"
                _nextAction.value = "POINT 1"
                _remainingSeconds.value = 0
                _countdownText.value = ""

                val delayMin = settingsRepository.getLatestSettings().cycleDelayMinutes
                EventLogManager.log(
                    EventLogManager.TAG_CYCLE,
                    "CYCLE STARTED: Интервал = $delayMin мин. Цикл #1"
                )

                cycleJob = launch {
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
                        resetState()
                    }
                }
            }
        }
        return true
    }

    /**
     * Полная немедленная остановка цикла.
     */
    fun stop() {
        coroutineScope.launch {
            cycleMutex.withLock {
                val jobToCancel = cycleJob
                cycleJob = null
                jobToCancel?.cancel()

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
     * Одиночное тестовое нажатие по точке.
     */
    fun testClick(pointId: Int) {
        coroutineScope.launch {
            val point = settingsRepository.getLatestSettings().getPointById(pointId)
            if (!point.isConfigured) {
                EventLogManager.log(
                    EventLogManager.TAG_CYCLE,
                    "ERROR: Точка $pointId не настроена (X=${point.x}, Y=${point.y})",
                    isError = true
                )
                return@launch
            }
            EventLogManager.log(
                EventLogManager.TAG_CYCLE,
                "TEST TAP POINT $pointId (${point.x.toInt()}, ${point.y.toInt()})"
            )
            try {
                val success = gestureExecutor.performTap(point.x, point.y)
                if (!success) {
                    EventLogManager.log(
                        EventLogManager.TAG_CYCLE,
                        "ERROR: Test tap failed for point $pointId",
                        isError = true
                    )
                }
            } catch (t: Throwable) {
                EventLogManager.log(
                    EventLogManager.TAG_CYCLE,
                    "ERROR: Исключение при тестовом жесте: ${t.message}",
                    isError = true
                )
            }
        }
    }

    /**
     * Основной непрерывный цикл:
     * 1. Отсчет фиксированного интервала (например 7 минут).
     * 2. Выполнение POINT 1.
     * 3. Выполнение POINT 2.
     * 4. Выполнение POINT 3.
     * 5. Повторение.
     */
    private suspend fun runLoop() {
        while (coroutineScope.isActive) {
            val currentCycle = _cycleNumber.value
            val settings = settingsRepository.getLatestSettings()

            // 1. Ожидание установленного интервала (от 1 до 15 минут)
            _nextAction.value = "POINT 1"
            waitCycleInterval(settings.cycleDelayMinutes)

            // 2. Выполнение POINT 1
            if (settings.point1.enabled) {
                _nextAction.value = if (settings.point2.enabled) "POINT 2" else "POINT 3"
                executePoint(settings.point1)
                safeDelay(1000L, "Пауза после POINT 1")
            }

            // 3. Выполнение POINT 2
            if (settings.point2.enabled) {
                _nextAction.value = if (settings.point3.enabled) "POINT 3" else "Ожидание следующего цикла"
                executePoint(settings.point2)
                safeDelay(1000L, "Пауза после POINT 2")
            }

            // 4. Выполнение POINT 3 (Старт в лобби)
            if (settings.point3.enabled) {
                _nextAction.value = "Запуск таймера следующего цикла"
                executePoint(settings.point3)
                _lastAction.value = "POINT 3 TAP (Цикл #$currentCycle)"
                EventLogManager.log(
                    EventLogManager.TAG_CYCLE,
                    "POINT 3 COMPLETED: Завершено выполнение серии кликов (Цикл #$currentCycle)"
                )
            }

            // 5. Переход к следующему интервалу
            _cycleNumber.value = currentCycle + 1
            _lastAction.value = "Цикл #$currentCycle завершен"
            EventLogManager.log(
                EventLogManager.TAG_CYCLE,
                "CYCLE #$currentCycle FINISHED -> Запуск интервала #${currentCycle + 1}"
            )
        }
    }

    /**
     * Отсчет фиксированного времени с обновлением таймера MM:SS каждую секунду.
     */
    private suspend fun waitCycleInterval(delayMinutes: Int) {
        _status.value = CycleStatus.WAITING_CYCLE
        _currentAction.value = "Таймер цикла ($delayMinutes мин)"

        val totalSeconds = delayMinutes.coerceIn(1, 15) * 60
        EventLogManager.log(
            EventLogManager.TAG_CYCLE,
            String.format("TIMER STARTED: Ожидание %02d:00 до выполнения кликов", delayMinutes)
        )

        var remaining = totalSeconds
        while (remaining > 0 && coroutineScope.isActive) {
            val min = remaining / 60
            val sec = remaining % 60
            val formatted = String.format("%02d:%02d", min, sec)

            _remainingSeconds.value = remaining
            _countdownText.value = formatted

            delay(1000L)
            remaining--
        }

        _remainingSeconds.value = 0
        _countdownText.value = ""
        _status.value = CycleStatus.RUNNING
    }

    /**
     * Выполнение нажатий для конкретной точки с защитой от сбоев.
     */
    private suspend fun executePoint(point: ClickPoint) {
        val count = point.clickCount.coerceIn(1, 10)

        for (i in 1..count) {
            val actionDesc = "POINT ${point.id} TAP ($i/$count)"
            _currentAction.value = actionDesc
            EventLogManager.log(EventLogManager.TAG_CYCLE, actionDesc)

            try {
                gestureExecutor.performTap(point.x, point.y)
                _lastAction.value = actionDesc
            } catch (t: Throwable) {
                EventLogManager.log(
                    EventLogManager.TAG_CYCLE,
                    "WARN: Ошибка жеста POINT ${point.id} ($i/$count): ${t.message}",
                    isError = true
                )
            }

            if (i < count) {
                var remaining = point.intervalSec
                while (remaining > 0 && coroutineScope.isActive) {
                    _remainingSeconds.value = remaining
                    _countdownText.value = "${remaining}s"
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
