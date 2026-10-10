package com.example.autoclicker.data

import android.content.Context
import com.example.autoclicker.engine.RunCoordinator
import com.example.autoclicker.service.MacroRecordingOverlayView

/**
 * Сбросы настроек. Перед любым сбросом останавливаются все работающие режимы.
 */
object ResetManager {

    private fun repo(context: Context) = SettingsRepository.getInstance(context.applicationContext)

    fun deleteAllMacros(context: Context) {
        RunCoordinator.stopAll(context, "удаление макросов")
        MacroRecordingOverlayView.dismissActive()
        repo(context).clearMacro()
        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "RESET: макрос удалён")
    }

    /** Сброс действий: макрос. */
    fun resetActions(context: Context) {
        RunCoordinator.stopAll(context, "сброс записи")
        MacroRecordingOverlayView.dismissActive()
        val r = repo(context)
        r.clearMacro()
        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "RESET: запись сброшена")
    }

    /** Умный режим: флаги, режим конфигов и выбор активного конфига. Файлы загруженных конфигов остаются. */
    fun resetSmartMode(context: Context) {
        RunCoordinator.stopAll(context, "сброс умного режима")
        repo(context).resetSmartFlags()
        val cm = SmartConfigManager.getInstance(context.applicationContext)
        cm.setMode(SmartConfigMode.DEFAULT_ONLY)
        cm.setActiveName("")
        SmartLearnedStore.clear()
        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "RESET: умный режим сброшен (встроенный конфиг)")
    }

    fun resetNeonBrightness(context: Context) {
        repo(context).resetNeonBrightness()
        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "RESET: яркость неона сброшена")
    }

    /** Сбросить всё: макросы, умный режим, яркость. Конфиги и логи не удаляются. */
    fun resetAll(context: Context) {
        RunCoordinator.stopAll(context, "сброс всех настроек")
        MacroRecordingOverlayView.dismissActive()
        val r = repo(context)
        r.clearMacro()
        r.resetSmartFlags()
        r.resetNeonBrightness()
        val cm = SmartConfigManager.getInstance(context.applicationContext)
        cm.setMode(SmartConfigMode.DEFAULT_ONLY)
        cm.setActiveName("")
        SmartLearnedStore.clear()
        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "RESET: сброшены все настройки")
    }

    /** Удалить все загруженные пользовательские конфиги (встроенный остаётся). */
    fun deleteAllCustomConfigs(context: Context) {
        RunCoordinator.stopAll(context, "удаление конфигов")
        val cm = SmartConfigManager.getInstance(context.applicationContext)
        for (name in cm.listConfigs()) {
            cm.delete(name)
        }
        cm.setMode(SmartConfigMode.DEFAULT_ONLY)
        cm.setActiveName("")
        SmartLearnedStore.clear()
        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "RESET: загруженные конфиги удалены")
    }
}
