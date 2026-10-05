package com.example.autoclicker

import android.app.Application
import com.example.autoclicker.data.LogFileManager

/**
 * Класс приложения: на старте процесса включает файловые логи
 * (запуск процесса, пульс, необработанные исключения).
 */
class AutoClickerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LogFileManager.init(this)
    }
}
