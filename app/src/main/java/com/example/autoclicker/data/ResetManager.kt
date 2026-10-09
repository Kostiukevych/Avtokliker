package com.example.autoclicker.data

import android.content.Context
import com.example.autoclicker.engine.RunCoordinator
import com.example.autoclicker.service.CalibrationOverlayView
import com.example.autoclicker.service.MacroRecordingOverlayView

/**
 * Сбросы настроек. Перед любым сбросом останавливаются все работающие режимы.
 * Подтверждающие диалоги («Вы уверены?») показывает интерфейс.
 */
object ResetManager {

    private fun repo(context: Context) = SettingsRepository.getInstance(context.applicationContext)

    fun resetPoint(context: Context, pointId: Int) {
        RunCoordinator.stopAll(context, "сброс точки $pointId")
        CalibrationOverlayView.dismissActive()
        repo(context).resetPoint(pointId)
        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "RESET: точка $pointId сброшена")
    }

    fun resetAllPoints(context: Context) {
        RunCoordinator.stopAll(context, "сброс всех точек")
        CalibrationOverlayView.dismissActive()
        repo(context).resetAllPoints()
        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "RESET: все точки сброшены")
    }

    fun resetSwipe(context: Context, swipeId: Int) {
        RunCoordinator.stopAll(context, "сброс свайпа $swipeId")
        CalibrationOverlayView.dismissActive()
        repo(context).resetSwipe(swipeId)
        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "RESET: свайп $swipeId сброшен")
    }

    fun resetAllSwipes(context: Context) {
        RunCoordinator.stopAll(context, "сброс всех свайпов")
        CalibrationOverlayView.dismissActive()
        repo(context).resetAllSwipes()
        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "RESET: все свайпы сброшены")
    }

    fun deleteAllMacros(context: Context) {
        RunCoordinator.stopAll(context, "удаление макросов")
        MacroRecordingOverlayView.dismissActive()
        repo(context).clearMacro()
        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "RESET: макрос удалён")
    }

    /** Сброс действий: точки, свайпы, макрос и группы запуска без затрагивания умного режима, таймера и неона. */
    fun resetActions(context: Context) {
        RunCoordinator.stopAll(context, "сброс точек, свайпов и записи")
        MacroRecordingOverlayView.dismissActive()
        CalibrationOverlayView.dismissActive()
        val r = repo(context)
        r.resetAllPoints()
        r.resetAllSwipes()
        r.clearMacro()
        r.resetRunGroups()
        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "RESET: точки, свайпы и запись сброшены")
    }

    /** Умный режим: флаги, режим конфигов и выбор активного конфига. Файлы загруженных конфигов остаются. */
    fun resetSmartMode(context: Context) {
        RunCoordinator.stopAll(context, "сброс умного режима")
        repo(context).resetSmartFlags()
        val cm = SmartConfigManager.getInstance(context.applicationContext)
        cm.setMode(SmartConfigMode.DEFAULT_ONLY)
        cm.setActiveName("")
        SmartLearnedStore.clear()
        JoystickRepository.getInstance(context).resetAll()
        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "RESET: умный режим сброшен (встроенный конфиг)")
    }

    fun resetCycleDelay(context: Context) {
        repo(context).resetCycleDelay()
        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "RESET: таймер цикла сброшен")
    }

    fun resetNeonBrightness(context: Context) {
        repo(context).resetNeonBrightness()
        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "RESET: яркость неона сброшена")
    }

    /** Сбросить всё: точки, свайпы, макросы, умный режим, таймер, группы запуска, яркость. Конфиги и логи не удаляются. */
    fun resetAll(context: Context) {
        RunCoordinator.stopAll(context, "сброс всех настроек")
        MacroRecordingOverlayView.dismissActive()
        CalibrationOverlayView.dismissActive()
        val r = repo(context)
        r.resetAllPoints()
        r.resetAllSwipes()
        r.clearMacro()
        r.resetSmartFlags()
        r.resetCycleDelay()
        r.resetNeonBrightness()
        r.resetRunGroups()
        val cm = SmartConfigManager.getInstance(context.applicationContext)
        cm.setMode(SmartConfigMode.DEFAULT_ONLY)
        cm.setActiveName("")
        SmartLearnedStore.clear()
        JoystickRepository.getInstance(context).resetAll()
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
