package com.example.autoclicker.engine

import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import com.example.autoclicker.R
import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.service.AccessibilityServiceHolder
import com.example.autoclicker.service.AutoClickForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Умный режим (Smart Mode) автокликера.
 *
 * Вместо фиксированного таймера анализирует экран и находит кнопки PUBG Mobile:
 * continue_mvp, continue_blue, start.
 */
class SmartEngine private constructor(
    private val context: Context,
    private val gestureExecutor: GestureExecutor,
    private val settingsRepository: SettingsRepository
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engineMutex = Mutex()
    private var engineJob: Job? = null

    var tapHooks: TapHooks? = null

    private val _status = MutableStateFlow(CycleStatus.STOPPED)
    val status: StateFlow<CycleStatus> = _status.asStateFlow()

    private val _currentAction = MutableStateFlow("Остановлен")
    val currentAction: StateFlow<String> = _currentAction.asStateFlow()

    private val _lastAction = MutableStateFlow("—")
    val lastAction: StateFlow<String> = _lastAction.asStateFlow()

    private val _nextAction = MutableStateFlow("Готов к запуску")
    val nextAction: StateFlow<String> = _nextAction.asStateFlow()

    private val _remainingSeconds = MutableStateFlow(0)
    val remainingSeconds: StateFlow<Int> = _remainingSeconds.asStateFlow()

    private val _countdownText = MutableStateFlow("")
    val countdownText: StateFlow<String> = _countdownText.asStateFlow()

    private val _cycleNumber = MutableStateFlow(0)
    val cycleNumber: StateFlow<Int> = _cycleNumber.asStateFlow()

    val isRunning: Boolean
        get() = _status.value != CycleStatus.STOPPED && engineJob?.isActive == true

    // Кеш оригинальных шаблонов из assets
    private var rawTemplates: List<RawTemplate>? = null
    // Кеш масштабированных и подготовленных для конкретного разрешения шаблонов
    private var cachedScreenWidth = 0
    private var cachedScreenHeight = 0
    private var precomputedTemplates: List<PrecomputedTemplate>? = null

    data class RawTemplate(
        val name: String,
        val bitmap: Bitmap,
        val isContinue: Boolean
    )

    data class PrecomputedTemplate(
        val name: String,
        val isContinue: Boolean,
        val tDownW: Int,
        val tDownH: Int,
        val tScaledW: Int,
        val tScaledH: Int,
        val tDiff: FloatArray,
        val sigmaT: Double
    )

    data class FoundMatch(
        val name: String,
        val clickX: Float,
        val clickY: Float,
        val score: Float,
        val isContinue: Boolean
    )

    fun start(): Boolean {
        if (!AccessibilityServiceHolder.isConnected) {
            EventLogManager.log(
                EventLogManager.TAG_AUTO_CLICKER,
                "ERROR: AccessibilityService unavailable. Запуск Smart Mode невозможен.",
                isError = true
            )
            return false
        }

        coroutineScope.launch {
            engineMutex.withLock {
                if (engineJob?.isActive == true) {
                    EventLogManager.log(
                        EventLogManager.TAG_AUTO_CLICKER,
                        "Smart Mode уже выполняется. Повторный START проигнорирован."
                    )
                    return@withLock
                }

                _cycleNumber.value = 1
                _status.value = CycleStatus.RUNNING
                _lastAction.value = "START (Smart)"
                _currentAction.value = "Умный режим: запуск..."
                _nextAction.value = "Поиск кнопок на экране"
                _countdownText.value = ""
                _remainingSeconds.value = 0

                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "SMART: Умный режим запущен (анализ экрана каждые 2 сек)"
                )

                val newJob = launch {
                    val currentJob = coroutineContext[Job]
                    try {
                        runLoop()
                    } catch (e: CancellationException) {
                        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "SMART: Остановлен пользователем")
                        throw e
                    } catch (t: Throwable) {
                        EventLogManager.log(
                            EventLogManager.TAG_AUTO_CLICKER,
                            "ERROR in SmartEngine: ${t.message}",
                            isError = true
                        )
                    } finally {
                        engineMutex.withLock {
                            if (engineJob === currentJob) {
                                engineJob = null
                                resetState()
                                clearScreensDir()
                            }
                        }
                    }
                }
                engineJob = newJob
            }
        }
        return true
    }

    fun stop() {
        coroutineScope.launch {
            engineMutex.withLock {
                val jobToCancel = engineJob
                engineJob = null
                jobToCancel?.cancelAndJoin()
                resetState()
                clearScreensDir()
                _lastAction.value = "STOP"
                EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "SMART: Остановлен")
            }
        }
    }

    private fun resetState() {
        _status.value = CycleStatus.STOPPED
        _currentAction.value = "Остановлен"
        _nextAction.value = "Готов к запуску"
        _remainingSeconds.value = 0
        _countdownText.value = ""
    }

    private suspend fun runLoop() {
        ensureTemplatesLoaded()

        var lastMatchTime = System.currentTimeMillis()
        val timeout5Min = 5 * 60 * 1000L

        while (true) {
            coroutineContext.ensureActive()

            val service = AccessibilityServiceHolder.service.value
            if (service == null) {
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "ERROR: AccessibilityService disconnected during Smart Mode",
                    isError = true
                )
                stop()
                break
            }

            _currentAction.value = "Умный режим: анализ экрана..."
            _nextAction.value = "Поиск кнопок"

            val screenshot = try {
                service.takeScreenshotBitmap()
            } catch (t: Throwable) {
                null
            }

            if (screenshot != null) {
                try {
                    // Отладка: сохранение последних 3 снимков
                    val settings = settingsRepository.getLatestSettings()
                    if (settings.isDebugScreenshots) {
                        saveDebugScreenshot(screenshot)
                    }

                    // Поиск шаблонов в порядке приоритета
                    val match = findMatchInScreenshot(screenshot)

                    if (match != null) {
                        lastMatchTime = System.currentTimeMillis()
                        val logText = "SMART: найдено ${match.name}, тап (${match.clickX.toInt()}, ${match.clickY.toInt()})"
                        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, logText)

                        _currentAction.value = "Нажатие ${match.name}..."
                        _lastAction.value = "${match.name} (${match.clickX.toInt()}, ${match.clickY.toInt()})"

                        performTapWithHooks(match.clickX, match.clickY)

                        val pauseMs = if (match.isContinue) 1500L else 4000L
                        _nextAction.value = "Пауза ${pauseMs / 1000f}с"
                        delay(pauseMs)
                    } else {
                        // Проверка таймаута 5 минут без совпадений
                        val elapsed = System.currentTimeMillis() - lastMatchTime
                        if (elapsed >= timeout5Min) {
                            EventLogManager.log(
                                EventLogManager.TAG_AUTO_CLICKER,
                                "SMART: экран не распознан",
                                isError = true
                            )
                            showUnrecognizedScreenNotification()
                            stop()
                            break
                        }

                        _currentAction.value = "Умный режим: ожидание..."
                        _nextAction.value = "Следующий снимок через 2с"
                        delay(2000L)
                    }
                } finally {
                    screenshot.recycle()
                }
            } else {
                // Если снимок не удалось сделать (например на API < 30 или сбой системы)
                val elapsed = System.currentTimeMillis() - lastMatchTime
                if (elapsed >= timeout5Min) {
                    EventLogManager.log(
                        EventLogManager.TAG_AUTO_CLICKER,
                        "SMART: экран не распознан",
                        isError = true
                    )
                    showUnrecognizedScreenNotification()
                    stop()
                    break
                }
                delay(2000L)
            }
        }
    }

    private suspend fun performTapWithHooks(x: Float, y: Float): Boolean {
        tapHooks?.beforeTap(0, x, y, 1, 1)
        val success = try {
            gestureExecutor.performTap(x, y)
        } catch (t: Throwable) {
            false
        }
        tapHooks?.afterTap(0, x, y, success, 1, 1)
        return success
    }

    private fun ensureTemplatesLoaded() {
        if (rawTemplates != null) return

        val templates = mutableListOf<RawTemplate>()
        // Порядок приоритета: continue_mvp, continue_blue, start
        val list = listOf(
            Triple("continue_mvp", "templates/continue_mvp.png", true),
            Triple("continue_blue", "templates/continue_blue.png", true),
            Triple("start", "templates/start.png", false)
        )

        for ((name, assetPath, isContinue) in list) {
            try {
                context.assets.open(assetPath).use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream)
                    if (bmp != null) {
                        templates.add(RawTemplate(name, bmp, isContinue))
                    }
                }
            } catch (e: Exception) {
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "ERROR: Не удалось загрузить шаблон $name: ${e.message}",
                    isError = true
                )
            }
        }
        rawTemplates = templates
    }

    private fun getPrecomputedTemplates(screenWidth: Int, screenHeight: Int): List<PrecomputedTemplate> {
        if (precomputedTemplates != null && cachedScreenWidth == screenWidth && cachedScreenHeight == screenHeight) {
            return precomputedTemplates!!
        }

        val raw = rawTemplates ?: return emptyList()
        val scale = screenWidth.toFloat() / 1340f
        val result = mutableListOf<PrecomputedTemplate>()

        for (template in raw) {
            val tScaledW = (template.bitmap.width * scale).roundToInt().coerceAtLeast(4)
            val tScaledH = (template.bitmap.height * scale).roundToInt().coerceAtLeast(4)
            val tDownW = (tScaledW / 4).coerceAtLeast(1)
            val tDownH = (tScaledH / 4).coerceAtLeast(1)

            val scaledBmp = Bitmap.createScaledBitmap(template.bitmap, tDownW, tDownH, true)
            val pixels = IntArray(tDownW * tDownH)
            scaledBmp.getPixels(pixels, 0, tDownW, 0, 0, tDownW, tDownH)
            scaledBmp.recycle()

            val tGray = FloatArray(tDownW * tDownH)
            var sumT = 0.0
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val gray = (r * 299 + g * 587 + b * 114) / 1000f
                tGray[i] = gray
                sumT += gray
            }

            val n = (tDownW * tDownH).toDouble()
            val meanT = sumT / n
            val tDiff = FloatArray(tDownW * tDownH)
            var sumDiffSq = 0.0
            for (i in tGray.indices) {
                val diff = (tGray[i] - meanT).toFloat()
                tDiff[i] = diff
                sumDiffSq += diff * diff
            }
            val sigmaT = sqrt(sumDiffSq)

            result.add(
                PrecomputedTemplate(
                    name = template.name,
                    isContinue = template.isContinue,
                    tDownW = tDownW,
                    tDownH = tDownH,
                    tScaledW = tScaledW,
                    tScaledH = tScaledH,
                    tDiff = tDiff,
                    sigmaT = sigmaT
                )
            )
        }

        cachedScreenWidth = screenWidth
        cachedScreenHeight = screenHeight
        precomputedTemplates = result
        return result
    }

    private fun findMatchInScreenshot(screenshot: Bitmap): FoundMatch? {
        val screenW = screenshot.width
        val screenH = screenshot.height

        val templates = getPrecomputedTemplates(screenW, screenH)
        if (templates.isEmpty()) return null

        val downW = (screenW / 4).coerceAtLeast(1)
        val downH = (screenH / 4).coerceAtLeast(1)

        val downScreenshot = Bitmap.createScaledBitmap(screenshot, downW, downH, true)
        val pixels = IntArray(downW * downH)
        downScreenshot.getPixels(pixels, 0, downW, 0, 0, downW, downH)
        downScreenshot.recycle()

        val imageGray = FloatArray(downW * downH)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            imageGray[i] = (r * 299 + g * 587 + b * 114) / 1000f
        }

        // Интегральные таблицы для сумм и сумм квадратов окна
        val intW = downW + 1
        val intH = downH + 1
        val sumI = DoubleArray(intW * intH)
        val sumI2 = DoubleArray(intW * intH)

        for (y in 0 until downH) {
            var rowSum = 0.0
            var rowSum2 = 0.0
            val rowOffset = y * downW
            val intRowOffset = (y + 1) * intW
            val prevIntRowOffset = y * intW
            for (x in 0 until downW) {
                val v = imageGray[rowOffset + x].toDouble()
                rowSum += v
                rowSum2 += v * v
                sumI[intRowOffset + (x + 1)] = sumI[prevIntRowOffset + (x + 1)] + rowSum
                sumI2[intRowOffset + (x + 1)] = sumI2[prevIntRowOffset + (x + 1)] + rowSum2
            }
        }

        fun getBoxSum(x: Int, y: Int, w: Int, h: Int, table: DoubleArray): Double {
            val x1 = x
            val y1 = y
            val x2 = x + w
            val y2 = y + h
            val d = table[y2 * intW + x2]
            val b = table[y1 * intW + x2]
            val c = table[y2 * intW + x1]
            val a = table[y1 * intW + x1]
            return d - b - c + a
        }

        // Ищем шаблоны в строгом порядке приоритета
        for (tmpl in templates) {
            if (tmpl.sigmaT <= 1e-4) continue
            val tW = tmpl.tDownW
            val tH = tmpl.tDownH
            if (downW < tW || downH < tH) continue

            val maxSearchX = downW - tW
            val maxSearchY = downH - tH
            val n = (tW * tH).toDouble()
            val tDiff = tmpl.tDiff
            val sigmaT = tmpl.sigmaT

            var bestScore = -1f
            var bestX = -1
            var bestY = -1

            // Быстрый проход со шагом 2
            var y = 0
            while (y <= maxSearchY) {
                var x = 0
                while (x <= maxSearchX) {
                    val sI = getBoxSum(x, y, tW, tH, sumI)
                    val sI2 = getBoxSum(x, y, tW, tH, sumI2)
                    val varianceI = sI2 - (sI * sI) / n
                    if (varianceI > 1e-4) {
                        val sigmaI = sqrt(varianceI)
                        var num = 0.0
                        var tIdx = 0
                        for (ty in 0 until tH) {
                            val imgRowStart = (y + ty) * downW + x
                            for (tx in 0 until tW) {
                                num += tDiff[tIdx++] * imageGray[imgRowStart + tx]
                            }
                        }
                        val score = (num / (sigmaT * sigmaI)).toFloat()
                        if (score > bestScore) {
                            bestScore = score
                            bestX = x
                            bestY = y
                        }
                    }
                    x += 2
                }
                y += 2
            }

            // Уточнение вокруг кандидата с шагом 1
            if (bestScore >= 0.70f) {
                val rMinX = (bestX - 2).coerceAtLeast(0)
                val rMaxX = (bestX + 2).coerceAtMost(maxSearchX)
                val rMinY = (bestY - 2).coerceAtLeast(0)
                val rMaxY = (bestY + 2).coerceAtMost(maxSearchY)

                for (ry in rMinY..rMaxY) {
                    for (rx in rMinX..rMaxX) {
                        if (rx == bestX && ry == bestY) continue
                        val sI = getBoxSum(rx, ry, tW, tH, sumI)
                        val sI2 = getBoxSum(rx, ry, tW, tH, sumI2)
                        val varianceI = sI2 - (sI * sI) / n
                        if (varianceI > 1e-4) {
                            val sigmaI = sqrt(varianceI)
                            var num = 0.0
                            var tIdx = 0
                            for (ty in 0 until tH) {
                                val imgRowStart = (ry + ty) * downW + rx
                                for (tx in 0 until tW) {
                                    num += tDiff[tIdx++] * imageGray[imgRowStart + tx]
                                }
                            }
                            val score = (num / (sigmaT * sigmaI)).toFloat()
                            if (score > bestScore) {
                                bestScore = score
                                bestX = rx
                                bestY = ry
                            }
                        }
                    }
                }
            }

            // Порог совпадения: >= 0.85
            if (bestScore >= 0.85f && bestX >= 0 && bestY >= 0) {
                val clickX = (bestX + tW / 2f) * (screenW.toFloat() / downW)
                val clickY = (bestY + tH / 2f) * (screenH.toFloat() / downH)
                return FoundMatch(
                    name = tmpl.name,
                    clickX = clickX,
                    clickY = clickY,
                    score = bestScore,
                    isContinue = tmpl.isContinue
                )
            }
        }

        return null
    }

    private fun showUnrecognizedScreenNotification() {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notif = NotificationCompat.Builder(context, AutoClickForegroundService.CHANNEL_ID)
                .setContentTitle("AutoClicker")
                .setContentText("Экран не распознан")
                .setSmallIcon(R.drawable.ic_stop)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(1002, notif)
        } catch (_: Exception) {}
    }

    private fun saveDebugScreenshot(bitmap: Bitmap) {
        try {
            val dir = File(context.cacheDir, "screens")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "screen_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            val files = dir.listFiles()?.filter { it.extension == "jpg" }?.sortedBy { it.lastModified() }
            if (files != null && files.size > 3) {
                val toDelete = files.take(files.size - 3)
                toDelete.forEach { it.delete() }
            }
        } catch (_: Exception) {}
    }

    private fun clearScreensDir() {
        try {
            val dir = File(context.cacheDir, "screens")
            dir.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {}
    }

    companion object {
        @Volatile
        private var INSTANCE: SmartEngine? = null

        fun getInstance(
            context: Context,
            gestureExecutor: GestureExecutor,
            settingsRepository: SettingsRepository
        ): SmartEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SmartEngine(
                    context.applicationContext,
                    gestureExecutor,
                    settingsRepository
                ).also { INSTANCE = it }
            }
        }
    }
}
