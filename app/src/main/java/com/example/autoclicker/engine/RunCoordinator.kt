package com.example.autoclicker.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.service.AutoClickForegroundService
import com.example.autoclicker.service.AccessibilityServiceHolder
import com.example.autoclicker.service.AccessibilityStatus

/**
 * Единая точка запуска и остановки всех режимов.
 *
 * START запускает выбранные группы в порядке settings.actionOrder
 * (по умолчанию smart → points → swipes). Группы можно включать вместе.
 * STOP останавливает всё (макрос, свайпы, умный режим, точки).
 */
object RunCoordinator {

    private val _isRunning = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isRunning: kotlinx.coroutines.flow.StateFlow<Boolean> = _isRunning

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
        if (AccessibilityStatus.isEnabledInSystem(app) && !AccessibilityServiceHolder.isConnected) {
            var waited = 0
            while (!AccessibilityServiceHolder.isConnected && waited < 3000) {
                try { Thread.sleep(200) } catch (_: InterruptedException) { break }
                waited += 200
            }
        }
        val started = mutableListOf<String>()
        val problems = mutableListOf<String>()

        // Порядок из настроек (по умолчанию smart → points → swipes)
        val order = s.actionOrder.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf("smart", "points", "swipes") }

        fun startSmart() {
            if (!s.isSmartMode) return
            if (smart.start()) {
                started.add("Умный режим")
                val joyRepo = com.example.autoclicker.data.JoystickRepository.getInstance(app)
                val js = joyRepo.getLatest()
                if (js.masterEnabled) {
                    if (js.hasAnyActive) {
                        if (JoystickController.getInstance(gestureExecutor, settingsRepo).start()) {
                            started.add("Джойстики")
                        }
                    } else {
                        problems.add("Джойстики включены, но не поставлены на экран")
                    }
                }
            } else problems.add("Умный режим не запущен: проверьте сервис доступности")
        }
        fun startPoints() {
            if (!s.runPoints) return
            if (s.hasActivePoints) {
                cycle.start()
                started.add("Точки")
            } else {
                problems.add("Для группы «Точки» нет включённых настроенных точек")
            }
        }
        fun startSwipes() {
            if (!s.runSwipes) return
            if (s.hasActiveSwipes) {
                if (swipes.start()) started.add("Свайпы")
                else problems.add("Свайпы не запущены: проверьте сервис доступности")
            } else {
                problems.add("Для группы «Свайпы» нет включённых настроенных свайпов")
            }
        }

        for (step in order) {
            when (step) {
                "smart" -> startSmart()
                "points" -> startPoints()
                "swipes" -> startSwipes()
            }
        }
        // На случай если в order чего-то не хватает — дозапуск включённых
        if (s.isSmartMode && "Умный режим" !in started) startSmart()
        if (s.runPoints && "Точки" !in started) startPoints()
        if (s.runSwipes && "Свайпы" !in started) startSwipes()

        EventLogManager.log(
            EventLogManager.TAG_AUTO_CLICKER,
            "RUN: порядок=${order.joinToString("→")}, запущено: ${started.joinToString(", ").ifEmpty { "—" }}"
        )

        if (started.isEmpty() && problems.isEmpty()) {
            problems.add("Не выбрано, что запускать (Точки, Свайпы или Умный режим)")
        }

        if (started.isNotEmpty()) {
            _isRunning.value = true
            settingsRepo.setRunActive(true)
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
    fun stopAll(context: Context, reason: String = "кнопка Стоп", clearActiveFlag: Boolean = true) {
        val app = context.applicationContext
        val settingsRepo = SettingsRepository.getInstance(app)
        val gestureExecutor = GestureExecutor()
        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "STOP: остановка всех режимов, причина: $reason")
        JoystickController.getInstance(gestureExecutor, settingsRepo).stop("stopAll: $reason")
        MacroController.getInstance(gestureExecutor, settingsRepo).stop()
        SwipeController.getInstance(gestureExecutor, settingsRepo).stop()
        SmartEngine.getInstance(app, gestureExecutor, settingsRepo).stop(reason)
        CycleController.getInstance(gestureExecutor, settingsRepo).stop()
        _isRunning.value = false
        releaseWakeLock()
        if (clearActiveFlag) {
            settingsRepo.setRunActive(false)
        }
    }
}
