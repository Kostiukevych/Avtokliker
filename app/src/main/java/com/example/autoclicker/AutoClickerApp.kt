package com.example.autoclicker

import android.app.Application
import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.data.LogFileManager
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.service.FloatingOverlayService
import android.content.Intent

class AutoClickerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LogFileManager.init(this)
        EventLogManager.log(EventLogManager.TAG_SYSTEM, "Application.onCreate")

        // Самовосстановление оверлея после убийства процесса (если пользователь не нажимал крестик)
        try {
            val repo = SettingsRepository.getInstance(this)
            if (repo.isOverlayWanted()) {
                EventLogManager.log(EventLogManager.TAG_SYSTEM, "Возобновление оверлея после перезапуска процесса")
                startService(Intent(this, FloatingOverlayService::class.java))
            }
        } catch (t: Throwable) {
            EventLogManager.logError(EventLogManager.TAG_SYSTEM, "Не удалось возобновить оверлей", t)
        }
    }
}
