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
import java.util.Locale
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

    // Кеш масштабированных и подготовленных для конкретного разрешения шаблонов (3 масштаба: 0.93, 1.0, 1.07)
    private var cachedScreenWidth = 0
    private var cachedScreenHeight = 0
    private var precomputedTemplates: List<PrecomputedMultiScaleTemplate>? = null

    data class RawTemplate(
        val name: String,
        val bitmap: Bitmap,
        val isContinue: Boolean
    )

    data class TemplateVariant(
        val scaleMultiplier: Float,
        val tDownW: Int,
        val tDownH: Int,
        val tDiff: FloatArray,
        val sigmaT: Double
    )

    data class PrecomputedMultiScaleTemplate(
        val name: String,
        val isContinue: Boolean,
        val variants: List<TemplateVariant>
    )

    data class FoundMatch(
        val name: String,
        val clickX: Float,
        val clickY: Float,
        val score: Float,
        val isContinue: Boolean
    )

    data class MatchScanResult(
        val match: FoundMatch?,
        val bestScoreMvp: Float,
        val bestScoreBlue: Float,
        val bestScoreStart: Float
    )

    private class DownsampledRoi(
        val roiX0: Int,
        val roiY0: Int,
        val downW: Int,
        val downH: Int,
        val gray: FloatArray,
        val sumI: DoubleArray,
        val sumI2: DoubleArray,
        val intW: Int
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
        var unmatchedPassCount = 0
        var hasLoggedScreenSize = false

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
                    if (!hasLoggedScreenSize) {
                        hasLoggedScreenSize = true
                        EventLogManager.log(
                            EventLogManager.TAG_AUTO_CLICKER,
                            "SMART: размер снимка ${screenshot.width}x${screenshot.height}"
                        )
                    }

                    // Отладка: сохранение последних 3 снимков
                    val settings = settingsRepository.getLatestSettings()
                    if (settings.isDebugScreenshots) {
                        saveDebugScreenshot(screenshot)
                    }

                    // Поиск шаблонов в порядке приоритета
                    val scanResult = findMatchInScreenshot(screenshot)
                    val match = scanResult.match

                    if (match != null) {
                        unmatchedPassCount = 0
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
                        unmatchedPassCount++
                        if (unmatchedPassCount % 3 == 0) {
                            val scoreStr = String.format(
                                Locale.US,
                                "SMART: лучшие score mvp=%.2f blue=%.2f start=%.2f",
                                scanResult.bestScoreMvp.coerceAtLeast(0f),
                                scanResult.bestScoreBlue.coerceAtLeast(0f),
                                scanResult.bestScoreStart.coerceAtLeast(0f)
                            )
                            EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, scoreStr)
                        }

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
                // Если снимок не удалось сделать
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

    /**
     * Предварительный расчет шаблонов в 3-х масштабах: base*0.93, base*1.0, base*1.07 (base = H / 800f).
     * Уменьшение в 2 раза с настоящим усреднением 2x2 box filter.
     */
    private fun getPrecomputedTemplates(screenWidth: Int, screenHeight: Int): List<PrecomputedMultiScaleTemplate> {
        if (precomputedTemplates != null && cachedScreenWidth == screenWidth && cachedScreenHeight == screenHeight) {
            return precomputedTemplates!!
        }

        val raw = rawTemplates ?: return emptyList()
        val baseScale = screenHeight / 800f
        val scaleMultipliers = floatArrayOf(0.93f, 1.0f, 1.07f)
        val result = mutableListOf<PrecomputedMultiScaleTemplate>()

        for (template in raw) {
            val variants = mutableListOf<TemplateVariant>()
            for (m in scaleMultipliers) {
                val scale = baseScale * m
                var targetW = (template.bitmap.width * scale).roundToInt().coerceAtLeast(4)
                var targetH = (template.bitmap.height * scale).roundToInt().coerceAtLeast(4)
                if (targetW % 2 != 0) targetW++
                if (targetH % 2 != 0) targetH++

                val downW = targetW / 2
                val downH = targetH / 2

                val scaledBmp = Bitmap.createScaledBitmap(template.bitmap, targetW, targetH, true)
                val pixels = IntArray(targetW * targetH)
                scaledBmp.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
                scaledBmp.recycle()

                val tGray = FloatArray(downW * downH)
                var sumT = 0.0
                for (dy in 0 until downH) {
                    val srcY0 = dy * 2
                    val srcY1 = srcY0 + 1
                    val r0 = srcY0 * targetW
                    val r1 = srcY1 * targetW
                    val dstOffset = dy * downW
                    for (dx in 0 until downW) {
                        val srcX0 = dx * 2
                        val srcX1 = srcX0 + 1
                        val p00 = pixels[r0 + srcX0]
                        val p01 = pixels[r0 + srcX1]
                        val p10 = pixels[r1 + srcX0]
                        val p11 = pixels[r1 + srcX1]

                        val g00 = (((p00 shr 16) and 0xFF) * 299 + ((p00 shr 8) and 0xFF) * 587 + (p00 and 0xFF) * 114)
                        val g01 = (((p01 shr 16) and 0xFF) * 299 + ((p01 shr 8) and 0xFF) * 587 + (p01 and 0xFF) * 114)
                        val g10 = (((p10 shr 16) and 0xFF) * 299 + ((p10 shr 8) and 0xFF) * 587 + (p10 and 0xFF) * 114)
                        val g11 = (((p11 shr 16) and 0xFF) * 299 + ((p11 shr 8) and 0xFF) * 587 + (p11 and 0xFF) * 114)

                        val avg = (g00 + g01 + g10 + g11) / 4000f
                        tGray[dstOffset + dx] = avg
                        sumT += avg
                    }
                }

                val n = (downW * downH).toDouble()
                val meanT = sumT / n
                val tDiff = FloatArray(downW * downH)
                var sumDiffSq = 0.0
                for (i in tGray.indices) {
                    val diff = (tGray[i] - meanT).toFloat()
                    tDiff[i] = diff
                    sumDiffSq += diff * diff
                }
                val sigmaT = sqrt(sumDiffSq)

                variants.add(
                    TemplateVariant(
                        scaleMultiplier = m,
                        tDownW = downW,
                        tDownH = downH,
                        tDiff = tDiff,
                        sigmaT = sigmaT
                    )
                )
            }

            result.add(
                PrecomputedMultiScaleTemplate(
                    name = template.name,
                    isContinue = template.isContinue,
                    variants = variants
                )
            )
        }

        cachedScreenWidth = screenWidth
        cachedScreenHeight = screenHeight
        precomputedTemplates = result
        return result
    }

    /**
     * Создает область ROI с уменьшением в 2 раза через честный 2x2 box filter и строит интегральные таблицы.
     */
    private fun createDownsampledRoi(
        screenshot: Bitmap,
        fracX0: Float,
        fracX1: Float,
        fracY0: Float,
        fracY1: Float
    ): DownsampledRoi? {
        val screenW = screenshot.width
        val screenH = screenshot.height

        val roiX0 = (screenW * fracX0).toInt().coerceIn(0, screenW - 1)
        val roiX1 = (screenW * fracX1).toInt().coerceIn(roiX0 + 1, screenW)
        val roiY0 = (screenH * fracY0).toInt().coerceIn(0, screenH - 1)
        val roiY1 = (screenH * fracY1).toInt().coerceIn(roiY0 + 1, screenH)

        val rawW = roiX1 - roiX0
        val rawH = roiY1 - roiY0

        val evenW = if (rawW % 2 != 0) rawW - 1 else rawW
        val evenH = if (rawH % 2 != 0) rawH - 1 else rawH

        val downW = evenW / 2
        val downH = evenH / 2
        if (downW < 2 || downH < 2) return null

        val pixels = IntArray(evenW * evenH)
        screenshot.getPixels(pixels, 0, evenW, roiX0, roiY0, evenW, evenH)

        val gray = FloatArray(downW * downH)
        for (dy in 0 until downH) {
            val srcY0 = dy * 2
            val srcY1 = srcY0 + 1
            val r0 = srcY0 * evenW
            val r1 = srcY1 * evenW
            val dstOffset = dy * downW
            for (dx in 0 until downW) {
                val srcX0 = dx * 2
                val srcX1 = srcX0 + 1
                val p00 = pixels[r0 + srcX0]
                val p01 = pixels[r0 + srcX1]
                val p10 = pixels[r1 + srcX0]
                val p11 = pixels[r1 + srcX1]

                val g00 = (((p00 shr 16) and 0xFF) * 299 + ((p00 shr 8) and 0xFF) * 587 + (p00 and 0xFF) * 114)
                val g01 = (((p01 shr 16) and 0xFF) * 299 + ((p01 shr 8) and 0xFF) * 587 + (p01 and 0xFF) * 114)
                val g10 = (((p10 shr 16) and 0xFF) * 299 + ((p10 shr 8) and 0xFF) * 587 + (p10 and 0xFF) * 114)
                val g11 = (((p11 shr 16) and 0xFF) * 299 + ((p11 shr 8) and 0xFF) * 587 + (p11 and 0xFF) * 114)

                gray[dstOffset + dx] = (g00 + g01 + g10 + g11) / 4000f
            }
        }

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
                val v = gray[rowOffset + x].toDouble()
                rowSum += v
                rowSum2 += v * v
                sumI[intRowOffset + (x + 1)] = sumI[prevIntRowOffset + (x + 1)] + rowSum
                sumI2[intRowOffset + (x + 1)] = sumI2[prevIntRowOffset + (x + 1)] + rowSum2
            }
        }

        return DownsampledRoi(
            roiX0 = roiX0,
            roiY0 = roiY0,
            downW = downW,
            downH = downH,
            gray = gray,
            sumI = sumI,
            sumI2 = sumI2,
            intW = intW
        )
    }

    /**
     * NCC сопоставление шаблона внутри области ROI.
     */
    private fun searchTemplateInRoi(
        roi: DownsampledRoi,
        variant: TemplateVariant
    ): Pair<Float, Pair<Float, Float>> {
        val tW = variant.tDownW
        val tH = variant.tDownH
        if (roi.downW < tW || roi.downH < tH || variant.sigmaT <= 1e-4) {
            return Pair(-1f, Pair(0f, 0f))
        }

        val maxSearchX = roi.downW - tW
        val maxSearchY = roi.downH - tH
        val n = (tW * tH).toDouble()
        val tDiff = variant.tDiff
        val sigmaT = variant.sigmaT
        val sumI = roi.sumI
        val sumI2 = roi.sumI2
        val intW = roi.intW
        val gray = roi.gray
        val downW = roi.downW

        fun getBoxSum(x: Int, y: Int, w: Int, h: Int): Double {
            val x1 = x
            val y1 = y
            val x2 = x + w
            val y2 = y + h
            val d = sumI[y2 * intW + x2]
            val b = sumI[y1 * intW + x2]
            val c = sumI[y2 * intW + x1]
            val a = sumI[y1 * intW + x1]
            return d - b - c + a
        }

        fun getBoxSum2(x: Int, y: Int, w: Int, h: Int): Double {
            val x1 = x
            val y1 = y
            val x2 = x + w
            val y2 = y + h
            val d = sumI2[y2 * intW + x2]
            val b = sumI2[y1 * intW + x2]
            val c = sumI2[y2 * intW + x1]
            val a = sumI2[y1 * intW + x1]
            return d - b - c + a
        }

        var bestScore = -1f
        var bestX = -1
        var bestY = -1

        // Проход со шагом 2 для быстрого сканирования
        var y = 0
        while (y <= maxSearchY) {
            var x = 0
            while (x <= maxSearchX) {
                val sI = getBoxSum(x, y, tW, tH)
                val sI2 = getBoxSum2(x, y, tW, tH)
                val varianceI = sI2 - (sI * sI) / n
                if (varianceI > 1e-4) {
                    val sigmaI = sqrt(varianceI)
                    var num = 0.0
                    var tIdx = 0
                    for (ty in 0 until tH) {
                        val rowOffset = (y + ty) * downW + x
                        for (tx in 0 until tW) {
                            num += tDiff[tIdx++] * gray[rowOffset + tx]
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

        // Уточнение вокруг лучшего кандидата с шагом 1
        if (bestScore >= 0.65f && bestX >= 0 && bestY >= 0) {
            val rMinX = (bestX - 2).coerceAtLeast(0)
            val rMaxX = (bestX + 2).coerceAtMost(maxSearchX)
            val rMinY = (bestY - 2).coerceAtLeast(0)
            val rMaxY = (bestY + 2).coerceAtMost(maxSearchY)

            for (ry in rMinY..rMaxY) {
                for (rx in rMinX..rMaxX) {
                    if (rx == bestX && ry == bestY) continue
                    val sI = getBoxSum(rx, ry, tW, tH)
                    val sI2 = getBoxSum2(rx, ry, tW, tH)
                    val varianceI = sI2 - (sI * sI) / n
                    if (varianceI > 1e-4) {
                        val sigmaI = sqrt(varianceI)
                        var num = 0.0
                        var tIdx = 0
                        for (ty in 0 until tH) {
                            val rowOffset = (ry + ty) * downW + rx
                            for (tx in 0 until tW) {
                                num += tDiff[tIdx++] * gray[rowOffset + tx]
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

        if (bestX >= 0 && bestY >= 0) {
            val clickX = roi.roiX0 + (bestX + tW / 2f) * 2f
            val clickY = roi.roiY0 + (bestY + tH / 2f) * 2f
            return Pair(bestScore, Pair(clickX, clickY))
        }

        return Pair(bestScore, Pair(0f, 0f))
    }

    /**
     * Поиск шаблонов в приоритетном порядке: continue_mvp, continue_blue, start.
     * Порог совпадения: 0.80.
     */
    private fun findMatchInScreenshot(screenshot: Bitmap): MatchScanResult {
        val templates = getPrecomputedTemplates(screenshot.width, screenshot.height)
        if (templates.isEmpty()) {
            return MatchScanResult(null, 0f, 0f, 0f)
        }

        val tmplMvp = templates.firstOrNull { it.name == "continue_mvp" }
        val tmplBlue = templates.firstOrNull { it.name == "continue_blue" }
        val tmplStart = templates.firstOrNull { it.name == "start" }

        var bestScoreMvp = 0f
        var bestMatchMvp: FoundMatch? = null

        var bestScoreBlue = 0f
        var bestMatchBlue: FoundMatch? = null

        var bestScoreStart = 0f
        var bestMatchStart: FoundMatch? = null

        // 1. Область Continue: x от 0.55*W до W, y от 0.70*H до H
        val continueRoi = createDownsampledRoi(screenshot, 0.55f, 1.0f, 0.70f, 1.0f)
        if (continueRoi != null) {
            if (tmplMvp != null) {
                for (v in tmplMvp.variants) {
                    val (score, coords) = searchTemplateInRoi(continueRoi, v)
                    if (score > bestScoreMvp) {
                        bestScoreMvp = score
                        bestMatchMvp = FoundMatch("continue_mvp", coords.first, coords.second, score, true)
                    }
                }
            }
            if (tmplBlue != null) {
                for (v in tmplBlue.variants) {
                    val (score, coords) = searchTemplateInRoi(continueRoi, v)
                    if (score > bestScoreBlue) {
                        bestScoreBlue = score
                        bestMatchBlue = FoundMatch("continue_blue", coords.first, coords.second, score, true)
                    }
                }
            }
        }

        // Приоритет 1: continue_mvp (порог 0.80)
        if (bestScoreMvp >= 0.80f && bestMatchMvp != null) {
            return MatchScanResult(bestMatchMvp, bestScoreMvp, bestScoreBlue, bestScoreStart)
        }

        // Приоритет 2: continue_blue (порог 0.80)
        if (bestScoreBlue >= 0.80f && bestMatchBlue != null) {
            return MatchScanResult(bestMatchBlue, bestScoreMvp, bestScoreBlue, bestScoreStart)
        }

        // 2. Область Start: x от 0 до 0.40*W, y от 0.65*H до H
        val startRoi = createDownsampledRoi(screenshot, 0.0f, 0.40f, 0.65f, 1.0f)
        if (startRoi != null && tmplStart != null) {
            for (v in tmplStart.variants) {
                val (score, coords) = searchTemplateInRoi(startRoi, v)
                if (score > bestScoreStart) {
                    bestScoreStart = score
                    bestMatchStart = FoundMatch("start", coords.first, coords.second, score, false)
                }
            }
        }

        // Приоритет 3: start (порог 0.80)
        if (bestScoreStart >= 0.80f && bestMatchStart != null) {
            return MatchScanResult(bestMatchStart, bestScoreMvp, bestScoreBlue, bestScoreStart)
        }

        return MatchScanResult(null, bestScoreMvp, bestScoreBlue, bestScoreStart)
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
