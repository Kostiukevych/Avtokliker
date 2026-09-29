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
 * Поддерживает вывод в Logcat с заданными тегами и сохраняет последние 30 событий для UI.
 */
object EventLogManager {
    const val TAG_AUTO_CLICKER = "AUTO_CLICKER"
    const val TAG_GESTURE = "GESTURE"
    const val TAG_CYCLE = "CYCLE"
    const val TAG_OVERLAY = "OVERLAY"
    const val TAG_ACCESSIBILITY = "ACCESSIBILITY"

    private const val MAX_LOG_ENTRIES = 30
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    @Synchronized
    fun log(tag: String, message: String, isError: Boolean = false) {
        if (isError) {
            Log.e(tag, message)
        } else {
            Log.d(tag, message)
        }

        val timestamp = timeFormat.format(Date())
        val formattedEntry = "$timestamp [$tag] $message"

        val currentList = _logs.value.toMutableList()
        currentList.add(0, formattedEntry)
        if (currentList.size > MAX_LOG_ENTRIES) {
            currentList.removeAt(currentList.lastIndex)
        }
        _logs.value = currentList
    }

    fun clear() {
        _logs.value = emptyList()
    }
}
