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

    /**
     * @param repeatCount число повторов; 0 = бесконечно (пока не Стоп).
     * Параметры можно менять в настройках во время работы — подхватятся на следующем круге.
     */
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

                // Сохраняем стартовые значения в prefs, чтобы слайдеры и цикл были синхронны
                settingsRepository.updateMacroConfig(
                    if (repeatCount <= 0) 0 else repeatCount.coerceIn(1, 999),
                    intervalSec.coerceIn(0, 300)
                )

                playbackJob = launch {
                    runMacroLoop(macro)
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

    private suspend fun runMacroLoop(macro: RecordedMacro) {
        var repeat = 0
        EventLogManager.log(
            EventLogManager.TAG_CYCLE,
            "MACRO START: жестов ${macro.strokes.size} (параметры из настроек, 0 повторов = бесконечно)"
        )

        while (coroutineScope.isActive && _isRunning.value) {
            val cfg = settingsRepository.getLatestSettings()
            // 0 = бесконечный цикл
            val totalRepeats = cfg.macroRepeatCount  // 0 = infinite
            val intervalSec = cfg.macroIntervalSec.coerceIn(0, 300)
            val infinite = totalRepeats <= 0

            if (!infinite && repeat >= totalRepeats) break

            repeat++
            _currentRepeat.value = repeat
            _currentStatus.value = if (infinite) {
                "Повтор $repeat (∞)"
            } else {
                "Повтор $repeat из $totalRepeats"
            }

            EventLogManager.log(
                EventLogManager.TAG_CYCLE,
                "MACRO LOOP: Проход $repeat" + if (infinite) " (∞)" else "/$totalRepeats"
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

            // Нужен ли ещё круг?
            val cfgAfter = settingsRepository.getLatestSettings()
            val stillInfinite = cfgAfter.macroRepeatCount <= 0
            val limit = cfgAfter.macroRepeatCount
            val more = stillInfinite || repeat < limit
            if (more && coroutineScope.isActive && _isRunning.value) {
                val waitSec = cfgAfter.macroIntervalSec.coerceIn(0, 300)
                val delayMs = if (waitSec <= 0) 200L else waitSec * 1000L
                _currentStatus.value = "Пауза ${delayMs / 1000}с до следующего повтора"
                delay(delayMs)
            } else {
                break
            }
        }

        _isRunning.value = false
        _currentStatus.value = "Завершено"
        EventLogManager.log(EventLogManager.TAG_CYCLE, "MACRO FINISHED")
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
