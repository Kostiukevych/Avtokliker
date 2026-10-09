package com.example.autoclicker.engine

import com.example.autoclicker.data.EventLogManager
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
 * Контроллер независимой фоновой автоматизации свайпов.
 * Запускает независимую корутину для каждого включенного и настроенного свайпа.
 */
class SwipeController private constructor(
    private val gestureExecutor: GestureExecutor,
    private val settingsRepository: SettingsRepository
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val swipeMutex = Mutex()
    private val activeSwipeJobs = mutableMapOf<Int, Job>()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    fun start(): Boolean {
        if (!AccessibilityServiceHolder.isConnected) {
            EventLogManager.log(
                EventLogManager.TAG_CYCLE,
                "ERROR: AccessibilityService unavailable. Запуск свайпов невозможен.",
                isError = true
            )
            return false
        }

        coroutineScope.launch {
            swipeMutex.withLock {
                stopInternal()

                val settings = settingsRepository.getLatestSettings()

                val enabledSwipes = settings.swipes.filter { it.enabled && it.isConfigured }
                if (enabledSwipes.isEmpty()) {
                    EventLogManager.log(
                        EventLogManager.TAG_CYCLE,
                        "SWIPES: Нет активных включенных свайпов для выполнения"
                    )
                    return@withLock
                }

                _isRunning.value = true
                EventLogManager.log(
                    EventLogManager.TAG_CYCLE,
                    "SWIPES STARTED: Запуск ${enabledSwipes.size} активных свайпов"
                )

                for (swipe in enabledSwipes) {
                    val swipeId = swipe.id
                    val job = launch {
                        runSwipeLoop(swipeId)
                    }
                    activeSwipeJobs[swipeId] = job
                }
            }
        }
        return true
    }

    fun stop() {
        coroutineScope.launch {
            swipeMutex.withLock {
                stopInternal()
            }
        }
    }

    private fun stopInternal() {
        if (activeSwipeJobs.isNotEmpty()) {
            activeSwipeJobs.values.forEach { it.cancel() }
            activeSwipeJobs.clear()
            _isRunning.value = false
            EventLogManager.log(EventLogManager.TAG_CYCLE, "SWIPES STOPPED: Остановка всех свайпов")
        } else {
            _isRunning.value = false
        }
    }

    private suspend fun runSwipeLoop(swipeId: Int) {
        val initial = settingsRepository.getLatestSettings().getSwipeById(swipeId)
        if (!initial.isConfigured) {
            EventLogManager.log(
                EventLogManager.TAG_CYCLE,
                "SWIPE #$swipeId SKIP: координаты не настроены",
                isError = true
            )
            return
        }

        EventLogManager.log(
            EventLogManager.TAG_CYCLE,
            "SWIPE #$swipeId LOOP ACTIVE: интервал ${initial.intervalSec}с, длит ${initial.durationMs}мс"
        )

        // Первый свайп выполняется через 1с после запуска
        delay(1000L)

        while (coroutineScope.isActive) {
            val fresh = settingsRepository.getLatestSettings().getSwipeById(swipeId)
            if (!fresh.enabled) {
                EventLogManager.log(EventLogManager.TAG_CYCLE, "SWIPE #$swipeId завершен (отключен)")
                break
            }

            if (fresh.isConfigured) {
                EventLogManager.log(
                    EventLogManager.TAG_CYCLE,
                    "СВАЙП $swipeId: по заданным координатам (${fresh.startX.toInt()}, ${fresh.startY.toInt()}) → (${fresh.endX.toInt()}, ${fresh.endY.toInt()}) за ${fresh.durationMs} мс. Наличие кнопки не проверяется"
                )
                val ok = try {
                    gestureExecutor.performSwipe(
                        fresh.startX,
                        fresh.startY,
                        fresh.endX,
                        fresh.endY,
                        fresh.durationMs
                    )
                } catch (t: Throwable) {
                    EventLogManager.log(
                        EventLogManager.TAG_CYCLE,
                        "СВАЙП $swipeId: СВАЙП НЕ ВЫПОЛНЕН: ошибка жеста: ${t.message}",
                        isError = true
                    )
                    false
                }
                if (ok) {
                    EventLogManager.log(EventLogManager.TAG_CYCLE, "СВАЙП $swipeId: СВАЙП ВЫПОЛНЕН")
                } else {
                    val reason = if (!com.example.autoclicker.service.AccessibilityServiceHolder.isConnected)
                        "сервис доступности отключён" else "жест отменён системой"
                    EventLogManager.log(
                        EventLogManager.TAG_CYCLE,
                        "СВАЙП $swipeId: СВАЙП НЕ ВЫПОЛНЕН: $reason",
                        isError = true
                    )
                }
            } else {
                EventLogManager.log(
                    EventLogManager.TAG_CYCLE,
                    "SWIPE #$swipeId SKIP: координаты сброшены",
                    isError = true
                )
            }

            val interval = fresh.intervalSec.coerceIn(1, 600)
            delay(interval * 1000L)
        }
    }

    fun testSwipe(swipeId: Int) {
        coroutineScope.launch {
            val fresh = settingsRepository.getLatestSettings().getSwipeById(swipeId)
            if (!fresh.isConfigured) {
                EventLogManager.log(
                    EventLogManager.TAG_CYCLE,
                    "TEST SWIPE #$swipeId ERROR: Свайп не настроен",
                    isError = true
                )
                return@launch
            }
            EventLogManager.log(
                EventLogManager.TAG_CYCLE,
                "TEST SWIPE #$swipeId: (${fresh.startX.toInt()}, ${fresh.startY.toInt()}) -> (${fresh.endX.toInt()}, ${fresh.endY.toInt()}) [${fresh.durationMs}мс]"
            )
            gestureExecutor.performSwipe(
                fresh.startX,
                fresh.startY,
                fresh.endX,
                fresh.endY,
                fresh.durationMs
            )
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: SwipeController? = null

        fun getInstance(
            gestureExecutor: GestureExecutor,
            settingsRepository: SettingsRepository
        ): SwipeController {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SwipeController(gestureExecutor, settingsRepository).also {
                    INSTANCE = it
                }
            }
        }
    }
}
