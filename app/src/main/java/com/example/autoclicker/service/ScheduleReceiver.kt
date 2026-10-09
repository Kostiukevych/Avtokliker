package com.example.autoclicker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.engine.GestureExecutor
import com.example.autoclicker.engine.MacroController
import com.example.autoclicker.engine.RunCoordinator

/**
 * Приёмник срабатывания отложенного запуска по расписанию (AlarmManager).
 */
class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        val repo = SettingsRepository.getInstance(app)
        val s = repo.getLatestSettings()

        EventLogManager.log(
            EventLogManager.TAG_AUTO_CLICKER,
            "SCHEDULE: alarm сработал, цель=${s.scheduleTarget}"
        )
        repo.clearSchedule()

        try {
            Toast.makeText(app, "Отложенный запуск кликера", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }

        when (s.scheduleTarget) {
            "macro" -> {
                val gestureExecutor = GestureExecutor()
                MacroController.getInstance(gestureExecutor, repo)
                    .start(s.macroRepeatCount, s.macroIntervalSec)
            }
            else -> {
                RunCoordinator.start(app)
            }
        }
    }
}
