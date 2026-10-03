package com.example.autoclicker.engine

import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.data.RecordedMacro
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.service.AccessibilityServiceHolder
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
 * Контроллер воспроизведения записанного макроса.
 * Управляет жизненным циклом воспроизведения, повторениями и отменой через корутины.
 */
class MacroController private constructor(
    private val gestureExecutor: GestureExecutor,
    private val settingsRepository: SettingsRepository
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private var playbackJob: Job? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _currentStatus = MutableStateFlow("Остановлен")
    val currentStatus: StateFlow<String> = _currentStatus.asStateFlow()

    private val _currentRepeat = MutableStateFlow(0)
    val currentRepeat: StateFlow<Int> = _currentRepeat.asStateFlow()

    fun start(repeatCount: Int = 1, intervalSec: Int = 0): Boolean {
        if (!AccessibilityServiceHolder.isConnected) {
            EventLogManager.log(
                EventLogManager.TAG_CYCLE,
                "ERROR: AccessibilityService недоступен для запуска макроса",
                isError = true
            )
            return false
        }

        val macro = settingsRepository.getLatestSettings().recordedMacro
        if (macro == null || !macro.isNotEmpty) {
            EventLogManager.log(
                EventLogManager.TAG_CYCLE,
                "ERROR: Макрос не записан или пуст",
                isError = true
            )
            return false
        }

        coroutineScope.launch {
            mutex.withLock {
                stopInternal()

                _isRunning.value = true
                _currentRepeat.value = 0
                _currentStatus.value = "Воспроизведение макроса"

                val safeRepeats = repeatCount.coerceAtLeast(1)
                playbackJob = launch {
                    runMacroLoop(macro, safeRepeats, intervalSec)
                }
            }
        }
        return true
    }

    fun stop() {
        coroutineScope.launch {
            mutex.withLock {
                stopInternal()
            }
        }
    }

    private fun stopInternal() {
        if (playbackJob != null) {
            playbackJob?.cancel()
            playbackJob = null
            _isRunning.value = false
            _currentStatus.value = "Остановлен"
            EventLogManager.log(EventLogManager.TAG_CYCLE, "MACRO STOPPED: Остановка воспроизведения")
        } else {
            _isRunning.value = false
            _currentStatus.value = "Остановлен"
        }
    }

    private suspend fun runMacroLoop(
        macro: RecordedMacro,
        totalRepeats: Int,
        intervalSec: Int
    ) {
        EventLogManager.log(
            EventLogManager.TAG_CYCLE,
            "MACRO START: повторов $totalRepeats, интервал ${intervalSec}с, жестов ${macro.strokes.size}"
        )

        for (repeat in 1..totalRepeats) {
            if (!coroutineScope.isActive) break

            _currentRepeat.value = repeat
            _currentStatus.value = if (totalRepeats > 1) {
                "Повтор $repeat из $totalRepeats"
            } else {
                "Воспроизведение (${macro.formattedDuration})"
            }

            EventLogManager.log(
                EventLogManager.TAG_CYCLE,
                "MACRO LOOP: Проход $repeat/$totalRepeats"
            )

            val success = gestureExecutor.performMacro(macro) {
                !coroutineScope.isActive || !_isRunning.value
            }

            if (!success) {
                EventLogManager.log(
                    EventLogManager.TAG_CYCLE,
                    "MACRO LOOP: Ошибка или прерывание на повторе $repeat",
                    isError = true
                )
                break
            }

            if (repeat < totalRepeats && coroutineScope.isActive) {
                val delayMs = maxOf(200L, intervalSec * 1000L)
                _currentStatus.value = "Пауза между повторами (${delayMs / 1000}с)"
                delay(delayMs)
            }
        }

        _isRunning.value = false
        _currentStatus.value = "Завершено"
        EventLogManager.log(EventLogManager.TAG_CYCLE, "MACRO FINISHED: Все повторы выполнены")
    }

    companion object {
        @Volatile
        private var INSTANCE: MacroController? = null

        fun getInstance(
            gestureExecutor: GestureExecutor,
            settingsRepository: SettingsRepository
        ): MacroController {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MacroController(gestureExecutor, settingsRepository).also {
                    INSTANCE = it
                }
            }
        }
    }
}
