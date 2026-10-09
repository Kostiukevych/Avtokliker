package com.example.autoclicker.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Файловые логи: один txt-файл на сутки (log_YYYY-MM-DD.txt) в filesDir/logs.
 * Хранятся 7 суток (сегодня и 6 предыдущих), на восьмой день самый старый файл удаляется.
 *
 * Также пишет диагностику жизни процесса: запуск процесса, «пульс» раз в 15 секунд
 * и необработанные исключения, чтобы было видно, почему работа остановилась.
 */
object LogFileManager {

    private const val DIR_NAME = "logs"
    private const val FILE_PREFIX = "log_"
    private const val FILE_EXT = ".txt"
    const val RETENTION_DAYS = 7
    private const val MAX_FILE_BYTES = 25L * 1024 * 1024
    private const val PREFS = "log_session"
    private const val KEY_LAST_ALIVE = "last_alive_ms"
    private const val KEY_SESSION_OPEN = "session_open"

    @Volatile
    private var appContext: Context? = null

    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LogFileWriter").apply { isDaemon = true }
    }
    private var heartbeat: ScheduledExecutorService? = null

    // Форматтеры используются только в потоке writer, кроме crash-обработчика (там свои копии)
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val lineFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private var lastCleanupDay = ""
    private var sizeWarned = false

    val isInitialized: Boolean get() = appContext != null

    /** Вызывать один раз при старте процесса (Application.onCreate). */
    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app

        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wasOpen = prefs.getBoolean(KEY_SESSION_OPEN, false)
        val lastAlive = prefs.getLong(KEY_LAST_ALIVE, 0L)
        prefs.edit().putBoolean(KEY_SESSION_OPEN, true).putLong(KEY_LAST_ALIVE, System.currentTimeMillis()).apply()

        append(System.currentTimeMillis(), "PROCESS", "=== Процесс приложения запущен ===", false)
        if (wasOpen && lastAlive > 0L) {
            val t = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(lastAlive))
            append(
                System.currentTimeMillis(), "PROCESS",
                "Прошлый процесс прекратил работу после $t (система выгрузила приложение, свайп из недавних или сбой). Работа режимов при этом остановилась.",
                true
            )
        }

        installCrashHandler()
        startHeartbeat(app)
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(sw))
                val now = System.currentTimeMillis()
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(now))
                val file = fileForTime(now, SimpleDateFormat("yyyy-MM-dd", Locale.US))
                if (file != null) {
                    FileWriter(file, true).use {
                        it.write("$ts [CRASH] E Необработанное исключение в потоке ${thread.name}:\n${sw}\n")
                    }
                }
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun startHeartbeat(app: Context) {
        heartbeat?.shutdownNow()
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val exec = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "LogHeartbeat").apply { isDaemon = true }
        }
        exec.scheduleWithFixedDelay({
            try {
                prefs.edit().putLong(KEY_LAST_ALIVE, System.currentTimeMillis()).apply()
            } catch (_: Throwable) {
            }
        }, 15, 15, TimeUnit.SECONDS)
        heartbeat = exec
    }

    private fun logsDir(): File? {
        val ctx = appContext ?: return null
        val dir = File(ctx.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun fileForTime(timeMs: Long, fmt: SimpleDateFormat): File? {
        val dir = logsDir() ?: return null
        return File(dir, FILE_PREFIX + fmt.format(Date(timeMs)) + FILE_EXT)
    }

    /** Асинхронная запись строки в файл текущих суток. Безопасно вызывать из любого потока. */
    fun append(timeMs: Long, tag: String, message: String, isError: Boolean) {
        if (appContext == null) return
        writer.execute {
            try {
                val file = fileForTime(timeMs, dayFormat) ?: return@execute
                val day = dayFormat.format(Date(timeMs))
                if (day != lastCleanupDay) {
                    lastCleanupDay = day
                    sizeWarned = false
                    cleanupOld()
                }
                if (file.exists() && file.length() > MAX_FILE_BYTES) {
                    if (!sizeWarned) {
                        sizeWarned = true
                        FileWriter(file, true).use {
                            it.write("${lineFormat.format(Date(timeMs))} [LOG] E Файл достиг лимита ${MAX_FILE_BYTES / 1024 / 1024} МБ, запись остановлена до следующих суток\n")
                        }
                    }
                    return@execute
                }
                val level = if (isError) "E" else "I"
                FileWriter(file, true).use {
                    it.write("${lineFormat.format(Date(timeMs))} [$tag] $level $message\n")
                }
            } catch (t: Throwable) {
                Log.e("LogFileManager", "Ошибка записи лога: ${t.message}")
            }
        }
    }

    /** Удаляет файлы старше 7 суток (остаются сегодня и 6 предыдущих дней). */
    fun cleanupOld() {
        val dir = logsDir() ?: return
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, -(RETENTION_DAYS - 1))
        val oldestKeep = cal.timeInMillis
        val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        dir.listFiles()?.forEach { f ->
            val name = f.name
            if (name.startsWith(FILE_PREFIX) && name.endsWith(FILE_EXT)) {
                val dayStr = name.removePrefix(FILE_PREFIX).removeSuffix(FILE_EXT)
                val ts = try {
                    parser.parse(dayStr)?.time
                } catch (_: Throwable) {
                    null
                }
                if (ts != null && ts < oldestKeep) {
                    f.delete()
                }
            }
        }
    }

    /** Файлы логов от новых к старым. */
    fun listLogFiles(): List<File> {
        val dir = logsDir() ?: return emptyList()
        return (dir.listFiles() ?: emptyArray())
            .filter { it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_EXT) }
            .sortedByDescending { it.name }
    }

    /** Весь текст логов, от старых суток к новым. Для большого объёма ограничивается maxChars с конца. */
    fun readAllText(maxChars: Int = Int.MAX_VALUE): String {
        flush()
        val files = listLogFiles().reversed()
        val sb = StringBuilder()
        for (f in files) {
            sb.append("===== ").append(f.name).append(" =====\n")
            try {
                sb.append(f.readText())
            } catch (t: Throwable) {
                sb.append("(не удалось прочитать: ${t.message})\n")
            }
            sb.append('\n')
        }
        val text = sb.toString()
        return if (text.length > maxChars) text.substring(text.length - maxChars) else text
    }

    fun totalSizeBytes(): Long = listLogFiles().sumOf { it.length() }

    /** Ждёт, пока все поставленные в очередь записи попадут в файл. */
    fun flush() {
        try {
            writer.submit(Runnable { }).get(2, TimeUnit.SECONDS)
        } catch (_: Throwable) {
        }
    }

    /** Удаляет все файлы логов. */
    fun clearAll() {
        flush()
        listLogFiles().forEach { it.delete() }
    }
}
