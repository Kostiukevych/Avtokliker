package com.example.autoclicker.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Менеджер системных логов (шаг 6).
 * Без шума по кадрам/сканам — только значимые события и ошибки.
 */
object EventLogManager {
    const val TAG_AUTO_CLICKER = "AUTO_CLICKER"
    const val TAG_GESTURE = "GESTURE"
    const val TAG_CYCLE = "CYCLE"
    const val TAG_OVERLAY = "OVERLAY"
    const val TAG_ACCESSIBILITY = "ACCESSIBILITY"
    const val TAG_SYSTEM = "SYSTEM"

    const val MAX_LOG_ENTRIES = 400
    private val timeFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    @Volatile private var engineStateForErrors: String = "—"

    /** Вызывать из SmartEngine при смене состояния — для ERROR-блоков. */
    fun setEngineStateHint(state: String) {
        engineStateForErrors = state
    }

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
        try {
            LogFileManager.append(now, tag, message, isError)
        } catch (_: Exception) {
        }

        val isNoise = !isError && (
            message.startsWith("SMART: score") ||
            message.startsWith("SMART: снимок") ||
            message.startsWith("SMART: время") ||
            message.startsWith("SMART: ступень")
        )
        if (isNoise) return

        val currentList = _logs.value.toMutableList()
        currentList.add(0, formattedEntry)
        if (currentList.size > MAX_LOG_ENTRIES) {
            currentList.removeAt(currentList.lastIndex)
        }
        _logs.value = currentList
    }

    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        val now = System.currentTimeMillis()
        val ts = timeFormat.format(Date(now))
        val sb = StringBuilder()

        var location = "unknown"
        val t = throwable
        if (t != null) {
            val ste = t.stackTrace.firstOrNull {
                it.className.startsWith("com.example.autoclicker")
            } ?: t.stackTrace.firstOrNull()
            if (ste != null) {
                val cls = ste.className.substringAfterLast('.')
                location = "$cls.${ste.methodName} (${ste.fileName}:${ste.lineNumber})"
            }
        } else {
            val ste = Throwable().stackTrace.firstOrNull {
                it.className.startsWith("com.example.autoclicker") &&
                    !it.className.contains("EventLogManager")
            }
            if (ste != null) {
                val cls = ste.className.substringAfterLast('.')
                location = "$cls.${ste.methodName} (${ste.fileName}:${ste.lineNumber})"
            }
        }

        sb.appendLine("=== ERROR === $ts | $location | $message")
        sb.appendLine("Engine state: $engineStateForErrors")
        if (t != null) {
            sb.appendLine(t.stackTraceToString())
            var cause = t.cause
            var depth = 0
            while (cause != null && depth < 5) {
                sb.appendLine("--- cause[${depth + 1}]: ${cause.javaClass.name}: ${cause.message}")
                sb.appendLine(cause.stackTraceToString())
                cause = cause.cause
                depth++
            }
        }

        val block = sb.toString().trimEnd()
        Log.e(tag, block)
        try {
            LogFileManager.append(now, tag, block, true)
        } catch (_: Exception) {
        }

        val currentList = _logs.value.toMutableList()
        currentList.add(0, block)
        if (currentList.size > MAX_LOG_ENTRIES) {
            currentList.removeAt(currentList.lastIndex)
        }
        _logs.value = currentList
    }

    fun clear() {
        _logs.value = emptyList()
    }

    fun clearAll() {
        _logs.value = emptyList()
        LogFileManager.clearAll()
    }
}
