package com.example.autoclicker.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.data.SettingsRepository

object ScheduleManager {
    private const val REQ = 44001

    private fun pending(context: Context): PendingIntent {
        val intent = Intent(context, ScheduleReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQ,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(context: Context, atEpochMs: Long) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(app)
        val show = PendingIntent.getActivity(
            app, REQ + 1,
            Intent(app, com.example.autoclicker.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(atEpochMs, show), pi)
            EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "SCHEDULE: alarm на $atEpochMs")
        } catch (e: Exception) {
            EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "SCHEDULE: ошибка alarm: ${e.message}", isError = true)
        }
    }

    fun cancel(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pending(context.applicationContext))
        } catch (_: Exception) {
        }
    }

    fun rescheduleIfNeeded(context: Context) {
        val repo = SettingsRepository.getInstance(context)
        val s = repo.getLatestSettings()
        if (s.scheduleEnabled && s.scheduleAtEpochMs > System.currentTimeMillis()) {
            schedule(context, s.scheduleAtEpochMs)
        }
    }
}
