package com.example.autoclicker.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Менеджер логов и событий приложения.
 * Поддерживает вывод в Logcat с заданными тегами и сохраняет последние $MAX_LOG_ENTRIES событий для UI.
 */
object EventLogManager {
    const val TAG_AUTO_CLICKER = "AUTO_CLICKER"
    const val TAG_GESTURE = "GESTURE"
    const val TAG_CYCLE = "CYCLE"
    const val TAG_OVERLAY = "OVERLAY"
    const val TAG_ACCESSIBILITY = "ACCESSIBILITY"

    const val MAX_LOG_ENTRIES = 300
    private val timeFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    @Synchronized
    fun log(tag: String, message: String, isError: Boolean = false) {
        if (isError) {
            Log.e(tag, message)
        } else {
            Log.d(tag, message)
        }

        val now = System.currentTimeMillis()
        val timestamp = timeFormat.format(Date(now))
        val formattedEntry = "$timestamp [$tag] $message"
        LogFileManager.append(now, tag, message, isError)

        val currentList = _logs.value.toMutableList()
        currentList.add(0, formattedEntry)
        if (currentList.size > MAX_LOG_ENTRIES) {
            currentList.removeAt(currentList.lastIndex)
        }
        _logs.value = currentList
    }

    /** Очищает только список в интерфейсе (файлы за 7 суток остаются). */
    fun clear() {
        _logs.value = emptyList()
    }

    /** Очищает список в интерфейсе И все файлы логов. */
    fun clearAll() {
        _logs.value = emptyList()
        LogFileManager.clearAll()
    }
}
