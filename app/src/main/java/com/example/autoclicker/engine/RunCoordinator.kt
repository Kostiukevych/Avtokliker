package com.example.autoclicker.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.service.AutoClickForegroundService

/**
 * Единая точка запуска и остановки всех режимов.
 *
 * START запускает ТОЛЬКО выбранные группы:
 *  - «Умный режим» (settings.isSmartMode) или «Точки» (settings.runPoints), эти два взаимоисключающие,
 *    при включённом умном режиме точки не запускаются;
 *  - «Свайпы» (settings.runSwipes), независимо от остальных.
 * STOP останавливает всё (макрос, свайпы, умный режим, точки).
 */
object RunCoordinator {

    private var wakeLock: PowerManager.WakeLock? = null

    /** Держит процессор активным, пока работают режимы (нужно разрешение WAKE_LOCK в манифесте). */
    @Synchronized
    private fun acquireWakeLock(context: Context) {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoClicker::RunLock").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
            EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "RUN: wake lock включён")
        } catch (t: Throwable) {
            EventLogManager.log(
                EventLogManager.TAG_AUTO_CLICKER,
                "RUN: wake lock не включён: ${t.message}",
                isError = true
            )
        }
    }

    @Synchronized
    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "RUN: wake lock выключен")
            }
        } catch (_: Throwable) {
        }
        wakeLock = null
    }

    data class StartResult(
        val started: List<String>,
        val problems: List<String>
    ) {
        val anyStarted: Boolean get() = started.isNotEmpty()
    }

    fun start(context: Context): StartResult {
        val app = context.applicationContext
        val settingsRepo = SettingsRepository.getInstance(app)
        val gestureExecutor = GestureExecutor()
        val cycle = CycleController.getInstance(gestureExecutor, settingsRepo)
        val smart = SmartEngine.getInstance(app, gestureExecutor, settingsRepo)
        val swipes = SwipeController.getInstance(gestureExecutor, settingsRepo)

        val s = settingsRepo.getLatestSettings()
        val started = mutableListOf<String>()
        val problems = mutableListOf<String>()

        if (s.isSmartMode) {
            if (smart.start()) {
                started.add("Умный режим")
            } else {
                problems.add("Умный режим не запущен: проверьте сервис доступности")
            }
        } else if (s.runPoints) {
            if (s.hasActivePoints) {
                cycle.start()
                started.add("Точки")
            } else {
                problems.add("Для группы «Точки» нет включённых настроенных точек")
            }
        }

        if (s.runSwipes) {
            if (s.hasActiveSwipes) {
                if (swipes.start()) {
                    started.add("Свайпы")
                } else {
                    problems.add("Свайпы не запущены: проверьте сервис доступности")
                }
            } else {
                problems.add("Для группы «Свайпы» нет включённых настроенных свайпов")
            }
        }

        if (started.isEmpty() && problems.isEmpty()) {
            problems.add("Не выбрано, что запускать (Точки, Свайпы или Умный режим)")
        }

        if (started.isNotEmpty()) {
            // Приоритет процесса и процессор: иначе в игре система может усыпить или выгрузить приложение
            try {
                AutoClickForegroundService.start(app)
            } catch (t: Throwable) {
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "RUN: ForegroundService не запущен: ${t.message}",
                    isError = true
                )
            }
            acquireWakeLock(app)
        }

        EventLogManager.log(
            EventLogManager.TAG_AUTO_CLICKER,
            "START: запущено [${started.joinToString(", ")}]" +
                if (problems.isEmpty()) "" else ", проблемы: ${problems.joinToString("; ")}",
            isError = started.isEmpty()
        )

        if (problems.isNotEmpty()) {
            val text = problems.joinToString("\n")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(app, text, Toast.LENGTH_LONG).show()
            }
        }
        return StartResult(started, problems)
    }

    /**
     * Остановка всех режимов. reason попадает в журнал.
     */
    fun stopAll(context: Context, reason: String = "кнопка Стоп") {
        val app = context.applicationContext
        val settingsRepo = SettingsRepository.getInstance(app)
        val gestureExecutor = GestureExecutor()
        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "STOP: остановка всех режимов, причина: $reason")
        MacroController.getInstance(gestureExecutor, settingsRepo).stop()
        SwipeController.getInstance(gestureExecutor, settingsRepo).stop()
        SmartEngine.getInstance(app, gestureExecutor, settingsRepo).stop(reason)
        CycleController.getInstance(gestureExecutor, settingsRepo).stop()
        releaseWakeLock()
    }
}
