package com.example.autoclicker.engine

import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
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
 * Умный режим (Smart Mode) для автоматизации PUBG Mobile:
 * 1. В лобби находит кнопку «НАЧАТЬ» (по цвету, форме и шаблону) и нажимает на неё.
 * 2. Ждет начала катки и перехода в матч.
 * 3. Во время/после катки находит кнопку «ПРОДОЛЖИТЬ» (MVP, синяя, желтая) и нажимает на неё.
 * 4. Возвращается в лобби и повторяет цикл.
 */
class SmartEngine private constructor(
    private val context: Context,
    private val gestureExecutor: GestureExecutor,
    private val settingsRepository: SettingsRepository
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engineJob: Job? = null
    private val engineMutex = Mutex()

    var tapHooks: TapHooks? = null

    init {
        com.example.autoclicker.data.SmartConfigManager.getInstance(context).onConfigChangedListener = {
            if (isRunning) {
                coroutineScope.launch {
                    stopInternal("смена настроек конфига")
                    start()
                }
            }
        }
    }

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

    // Диагностика: причина завершения цикла и счётчики для heartbeat
    @Volatile
    private var endReason: String? = null
    private var scanCount = 0L
    private var nullShotCount = 0L
    private var matchCount = 0L

    private fun callerInfo(): String {
        return try {
            Throwable().stackTrace
                .filter { it.className.startsWith("com.example.autoclicker") && !it.className.contains("SmartEngine") }
                .take(2)
                .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        } catch (t: Throwable) {
            ""
        }
    }

    enum class SmartState {
        LOBBY_SEARCH_START,       // В лобби, поиск кнопки «НАЧАТЬ»
        WAITING_MATCH_START,      // «НАЧАТЬ» нажата, ожидание подбора/загрузки матча
        IN_MATCH_SEARCH_CONTINUE, // Матч идет, поиск кнопки «ПРОДОЛЖИТЬ» после катки
        POST_MATCH_TAP_CONTINUE   // Нажатие серии «ПРОДОЛЖИТЬ» (MVP -> статистика -> лобби)
    }

    // Кеш оригинальных шаблонов из assets
    private var rawTemplates: List<RawTemplate>? = null

    // Кеш масштабированных и подготовленных для конкретного разрешения шаблонов (5 масштабов)
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
        val isContinue: Boolean,
        val afterDelayMs: Long = 3000L,
        val rule: com.example.autoclicker.data.SmartRule? = null
    )

    data class RuleTemplate(
        val rule: com.example.autoclicker.data.SmartRule,
        val rawBitmap: Bitmap,
        val variants: List<TemplateVariant>
    )

    private val croppedBitmapsCache = mutableMapOf<String, Bitmap>()
    private var cachedRuleTemplates: List<RuleTemplate>? = null
    private var cachedRulesHash: Int = 0

    data class CandidateScanResult(
        val bestStart: FoundMatch?,
        val bestContinue: FoundMatch?,
        val totalCandidates: Int,
        val debugSummary: String
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
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context,
                    "Включите сервис доступности AutoClicker в настройках Android",
                    Toast.LENGTH_LONG
                ).show()
            }
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

                endReason = null
                scanCount = 0L
                nullShotCount = 0L
                matchCount = 0L
                _cycleNumber.value = 1
                _status.value = CycleStatus.RUNNING
                _lastAction.value = "START (Smart)"
                _currentAction.value = "Умный режим: запуск..."
                _nextAction.value = "Поиск кнопок на экране"
                _countdownText.value = ""
                _remainingSeconds.value = 0

                val configManager = com.example.autoclicker.data.SmartConfigManager.getInstance(context)
                val effectiveConfig = configManager.buildEffectiveRules()

                val (realW, realH) = getRealScreenSize()
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "SMART: Умный режим запущен. Разрешение экрана: ${realW}x${realH}"
                )
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "SMART: режим ${effectiveConfig.mode.name}, правил ${effectiveConfig.rules.size} (встроенных ${effectiveConfig.builtinCount}, своих ${effectiveConfig.customCount})"
                )
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "SMART: сервис доступности подключён, API=${Build.VERSION.SDK_INT}"
                )

                val newJob = launch {
                    val currentJob = coroutineContext[Job]
                    try {
                        runLoop(effectiveConfig)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        val projectTrace = t.stackTrace.firstOrNull { it.className.startsWith("com.example.autoclicker") }
                            ?: t.stackTrace.firstOrNull()
                        val traceStr = projectTrace?.toString() ?: "unknown"
                        EventLogManager.log(
                            EventLogManager.TAG_AUTO_CLICKER,
                            "SMART: ОШИБКА в цикле: ${t.javaClass.simpleName}: ${t.message ?: ""} @ $traceStr",
                            isError = true
                        )
                        endReason = "необработанная ошибка: ${t.javaClass.simpleName}"
                        EventLogManager.log(
                            EventLogManager.TAG_AUTO_CLICKER,
                            "SMART: остановка, причина: ошибки"
                        )
                    } finally {
                        EventLogManager.log(
                            EventLogManager.TAG_AUTO_CLICKER,
                            "SMART: цикл завершён, причина: ${endReason ?: "внешняя отмена корутины"}"
                        )
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

    suspend fun stopInternal(reason: String = "кнопка Стоп", caller: String = "") {
        val jobToCancel: Job?
        engineMutex.withLock {
            jobToCancel = engineJob
            engineJob = null
            endReason = reason
            resetState()
            clearScreensDir()
            _lastAction.value = "STOP"
            val who = if (caller.isNotEmpty()) " (вызвал: $caller)" else ""
            EventLogManager.log(
                EventLogManager.TAG_AUTO_CLICKER,
                "SMART: остановка, причина: $reason$who"
            )
        }
        jobToCancel?.cancelAndJoin()
    }

    fun stop(reason: String = "кнопка Стоп") {
        val caller = callerInfo()
        coroutineScope.launch {
            stopInternal(reason, caller)
        }
    }

    private fun resetState() {
        _status.value = CycleStatus.STOPPED
        _currentAction.value = "Остановлен"
        _nextAction.value = "Готов к запуску"
        _remainingSeconds.value = 0
        _countdownText.value = ""
    }

    private fun getRealScreenSize(): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                return Pair(bounds.width(), bounds.height())
            } else {
                @Suppress("DEPRECATION")
                val display = wm.defaultDisplay
                val size = Point()
                @Suppress("DEPRECATION")
                display.getRealSize(size)
                return Pair(size.x, size.y)
            }
        }
        val dm = context.resources.displayMetrics
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    private suspend fun runLoop(effectiveConfig: com.example.autoclicker.data.EffectiveConfig) {
        val (realW, realH) = getRealScreenSize()
        val ruleTemplates = ensureRuleTemplates(effectiveConfig.rules, realW, realH)

        var lastMatchTime = System.currentTimeMillis()
        val idleTimeoutMs = effectiveConfig.idleTimeoutMin * 60 * 1000L
        var lastIdleWarnTime = System.currentTimeMillis()
        var lastHeartbeatTime = System.currentTimeMillis()
        var serviceLostSince = 0L
        var consecutiveErrors = 0
        var lastLoggedSize = ""

        val hasBuiltin = effectiveConfig.rules.any { it.builtin }

        while (true) {
            try {
                coroutineContext.ensureActive()
                val loopStart = System.currentTimeMillis()

                val service = AccessibilityServiceHolder.service.value
                if (service == null) {
                    val nowLost = System.currentTimeMillis()
                    if (serviceLostSince == 0L) {
                        serviceLostSince = nowLost
                        EventLogManager.log(
                            EventLogManager.TAG_AUTO_CLICKER,
                            "SMART: сервис доступности отключён, жду переподключения (до 5 мин)",
                            isError = true
                        )
                    }
                    if (nowLost - serviceLostSince > 5 * 60 * 1000L) {
                        endReason = "сервис доступности недоступен более 5 минут"
                        EventLogManager.log(
                            EventLogManager.TAG_AUTO_CLICKER,
                            "SMART: остановка, причина: $endReason"
                        )
                        resetState()
                        clearScreensDir()
                        return
                    }
                    _currentAction.value = "Ожидание сервиса доступности..."
                    delay(2000L)
                    continue
                }
                if (serviceLostSince != 0L) {
                    EventLogManager.log(
                        EventLogManager.TAG_AUTO_CLICKER,
                        "SMART: сервис доступности снова подключён (простой ${(System.currentTimeMillis() - serviceLostSince) / 1000} с)"
                    )
                    serviceLostSince = 0L
                }

                _currentAction.value = "Поиск правил (${effectiveConfig.rules.size} шт.)..."

                scanCount++
                val screenshot = service.takeScreenshotBitmap()
                if (screenshot == null) nullShotCount++
                if (screenshot != null && !screenshot.isRecycled) {
                    try {
                        val sizeStr = "${screenshot.width}x${screenshot.height}"
                        if (sizeStr != lastLoggedSize) {
                            lastLoggedSize = sizeStr
                            EventLogManager.log(
                                EventLogManager.TAG_AUTO_CLICKER,
                                "SMART: снимок ${sizeStr} (экран ${realW}x${realH})"
                            )
                        }

                        val settings = settingsRepository.getLatestSettings()
                        if (settings.isDebugScreenshots) {
                            saveDebugScreenshot(screenshot)
                        }

                        // Поиск кандидатов в порядке приоритета
                        var match: FoundMatch? = null

                        // Если есть встроенное правило "start", проверяем желтую кнопку лобби
                        if (hasBuiltin && effectiveConfig.rules.any { it.builtin && it.id == "start" }) {
                            val yellowStart = detectYellowButton(screenshot, 0.0f, 0.42f, 0.65f, 1.0f, "start_yellow", false)
                            if (yellowStart != null && yellowStart.score >= 0.78f) {
                                match = yellowStart
                            }
                        }

                        // Проверяем шаблоны по правилам
                        if (match == null) {
                            for (rt in ruleTemplates) {
                                val found = findMatchInScreenshot(screenshot, rt)
                                if (found != null) {
                                    match = found
                                    break
                                }
                            }
                        }

                        // Если есть встроенные правила "continue", проверяем цвета
                        if (match == null && hasBuiltin && effectiveConfig.rules.any { it.builtin && it.id.startsWith("continue") }) {
                            val blueContinue = detectBlueButton(screenshot, 0.50f, 1.0f, 0.65f, 1.0f, "continue_blue_color", true)
                            if (blueContinue != null && blueContinue.score >= 0.78f) {
                                match = blueContinue
                            } else {
                                val yellowContinue = detectYellowButton(screenshot, 0.50f, 1.0f, 0.65f, 1.0f, "continue_yellow_color", true)
                                if (yellowContinue != null && yellowContinue.score >= 0.78f) {
                                    match = yellowContinue
                                }
                            }
                        }

                        if (match != null) {
                            lastMatchTime = System.currentTimeMillis()
                            lastIdleWarnTime = lastMatchTime
                            matchCount++

                            val scaleX = if (screenshot.width > 0) realW.toFloat() / screenshot.width else 1f
                            val scaleY = if (screenshot.height > 0) realH.toFloat() / screenshot.height else 1f
                            val finalX = match.clickX * scaleX
                            val finalY = match.clickY * scaleY

                            EventLogManager.log(
                                EventLogManager.TAG_AUTO_CLICKER,
                                "SMART: Выбрано правило «${match.name}» (score ${(match.score * 100).toInt()}%), клик в (${finalX.toInt()}, ${finalY.toInt()})"
                            )

                            _currentAction.value = "Нажатие «${match.name}»..."
                            _lastAction.value = "${match.name} (${finalX.toInt()}, ${finalY.toInt()})"

                            val tapOk = performTapWithHooks(finalX, finalY)
                            EventLogManager.log(
                                EventLogManager.TAG_AUTO_CLICKER,
                                "SMART: результат dispatchGesture -> ${if (tapOk) "completed (нажатие прошло)" else "cancelled (жест отклонен)"}"
                            )

                            val delayMs = match.afterDelayMs
                            if (delayMs > 0) {
                                val totalSec = (delayMs / 1000).toInt()
                                if (totalSec > 1) {
                                    for (sec in totalSec downTo 1) {
                                        coroutineContext.ensureActive()
                                        _remainingSeconds.value = sec
                                        _countdownText.value = "${sec}с"
                                        delay(1000L)
                                    }
                                    _remainingSeconds.value = 0
                                    _countdownText.value = ""
                                } else {
                                    delay(delayMs)
                                }
                            }
                        } else {
                            // Идёт матч или экран не распознан: режим НЕ останавливается сам,
                            // только пишет предупреждение раз в idleTimeoutMin минут.
                            val nowIdle = System.currentTimeMillis()
                            if (nowIdle - lastMatchTime >= idleTimeoutMs && nowIdle - lastIdleWarnTime >= idleTimeoutMs) {
                                lastIdleWarnTime = nowIdle
                                EventLogManager.log(
                                    EventLogManager.TAG_AUTO_CLICKER,
                                    "SMART: ${(nowIdle - lastMatchTime) / 60000} мин нет совпадений (идёт матч или экран не распознан), работа продолжается"
                                )
                            }

                            _nextAction.value = "Следующий снимок через 1с"
                            val spent = System.currentTimeMillis() - loopStart
                            delay((effectiveConfig.scanIntervalMs - spent).coerceAtLeast(50L))
                        }
                    } finally {
                        if (!screenshot.isRecycled) {
                            screenshot.recycle()
                        }
                    }
                } else {
                    _currentAction.value = "Умный режим: ожидание снимка..."
                    _nextAction.value = "Следующий снимок через 1с"
                    val spentNull = System.currentTimeMillis() - loopStart
                    delay((effectiveConfig.scanIntervalMs - spentNull).coerceAtLeast(50L))
                }

                consecutiveErrors = 0

                val nowHb = System.currentTimeMillis()
                if (nowHb - lastHeartbeatTime >= 60_000L) {
                    lastHeartbeatTime = nowHb
                    EventLogManager.log(
                        EventLogManager.TAG_AUTO_CLICKER,
                        "SMART: работает. Снимков: $scanCount (неудачных $nullShotCount), совпадений: $matchCount, последнее совпадение ${(nowHb - lastMatchTime) / 1000} с назад"
                    )
                }

            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                consecutiveErrors++
                val projectTrace = t.stackTrace.firstOrNull { it.className.startsWith("com.example.autoclicker") }
                    ?: t.stackTrace.firstOrNull()
                val traceStr = projectTrace?.toString() ?: "unknown"
                val errName = t.javaClass.simpleName
                val errMsg = t.message ?: "нет сообщения"

                _currentAction.value = "Ошибка: $errMsg"

                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "SMART: ОШИБКА в цикле: $errName: $errMsg @ $traceStr",
                    isError = true
                )

                if (consecutiveErrors >= 10) {
                    EventLogManager.log(
                        EventLogManager.TAG_AUTO_CLICKER,
                        "SMART: слишком много ошибок подряд",
                        isError = true
                    )
                    endReason = "10 ошибок подряд"
                    EventLogManager.log(
                        EventLogManager.TAG_AUTO_CLICKER,
                        "SMART: остановка, причина: ошибки"
                    )
                    resetState()
                    clearScreensDir()
                    return
                }

                delay(effectiveConfig.scanIntervalMs)
            }
        }
    }

    private fun ensureRuleTemplates(
        effectiveRules: List<com.example.autoclicker.data.SmartRule>,
        screenWidth: Int,
        screenHeight: Int
    ): List<RuleTemplate> {
        val rulesHash = effectiveRules.hashCode()
        if (cachedRuleTemplates != null &&
            cachedRulesHash == rulesHash &&
            cachedScreenWidth == screenWidth &&
            cachedScreenHeight == screenHeight &&
            cachedRuleTemplates!!.all { !it.rawBitmap.isRecycled }
        ) {
            return cachedRuleTemplates!!
        }

        val result = mutableListOf<RuleTemplate>()
        val H = minOf(screenWidth, screenHeight).toFloat()
        val scaleMultipliers = floatArrayOf(0.93f, 1.0f, 1.07f)

        for (rule in effectiveRules) {
            val bmp = loadRuleBitmap(rule) ?: continue
            val baseScale = H / rule.refHeight
            val variants = mutableListOf<TemplateVariant>()

            for (m in scaleMultipliers) {
                val scale = baseScale * m
                var targetW = (bmp.width * scale).roundToInt().coerceAtLeast(4)
                var targetH = (bmp.height * scale).roundToInt().coerceAtLeast(4)
                if (targetW % 2 != 0) targetW++
                if (targetH % 2 != 0) targetH++

                val downW = targetW / 2
                val downH = targetH / 2

                val scaledBmp = if (targetW == bmp.width && targetH == bmp.height) {
                    bmp
                } else {
                    Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
                }
                val pixels = IntArray(targetW * targetH)
                scaledBmp.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
                if (scaledBmp !== bmp && !scaledBmp.isRecycled) {
                    scaledBmp.recycle()
                }

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

            result.add(RuleTemplate(rule, bmp, variants))
        }

        cachedRuleTemplates = result
        cachedRulesHash = rulesHash
        cachedScreenWidth = screenWidth
        cachedScreenHeight = screenHeight
        return result
    }

    private fun loadRuleBitmap(rule: com.example.autoclicker.data.SmartRule): Bitmap? {
        if (rule.builtin) {
            val assetPath = rule.imageFile ?: return null
            return try {
                context.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                null
            }
        }

        if (!rule.imageFile.isNullOrEmpty() && rule.configDir != null) {
            val file = File(rule.configDir, File(rule.imageFile).name)
            if (file.exists()) {
                return try {
                    BitmapFactory.decodeFile(file.absolutePath)
                } catch (e: Exception) {
                    null
                }
            }
        }

        if (!rule.imageSource.isNullOrEmpty() && rule.imageBox != null && rule.configDir != null) {
            val cacheKey = "${rule.configDir.name}_${rule.id}"
            val cached = croppedBitmapsCache[cacheKey]
            if (cached != null && !cached.isRecycled) {
                return cached
            }

            val srcFile = File(rule.configDir, File(rule.imageSource).name)
            if (srcFile.exists()) {
                try {
                    val fullBmp = BitmapFactory.decodeFile(srcFile.absolutePath) ?: return null
                    val srcW = fullBmp.width
                    val srcH = fullBmp.height
                    val box = rule.imageBox
                    val bX = (box[0] * srcW).toInt().coerceIn(0, srcW - 1)
                    val bY = (box[1] * srcH).toInt().coerceIn(0, srcH - 1)
                    val bW = ((box[2] - box[0]) * srcW).toInt().coerceIn(4, srcW - bX)
                    val bH = ((box[3] - box[1]) * srcH).toInt().coerceIn(4, srcH - bY)
                    val cropped = Bitmap.createBitmap(fullBmp, bX, bY, bW, bH)
                    if (cropped !== fullBmp && !fullBmp.isRecycled) {
                        fullBmp.recycle()
                    }
                    croppedBitmapsCache[cacheKey] = cropped
                    return cropped
                } catch (e: Exception) {
                    return null
                }
            }
        }

        return null
    }

    private fun findMatchInScreenshot(
        screenshot: Bitmap,
        ruleTemplate: RuleTemplate
    ): FoundMatch? {
        val rule = ruleTemplate.rule
        val reg = rule.getEffectiveRegion()
        val roi = createDownsampledRoi(screenshot, reg[0], reg[2], reg[1], reg[3]) ?: return null

        var bestScore = -1f
        var bestCoords = Pair(0f, 0f)

        for (v in ruleTemplate.variants) {
            val (score, coords) = searchTemplateInRoi(roi, v)
            if (score > bestScore) {
                bestScore = score
                bestCoords = coords
            }
        }

        if (bestScore >= rule.threshold) {
            val (clickX, clickY) = if (rule.tapTarget == "fixed" && rule.tapX != null && rule.tapY != null) {
                Pair(rule.tapX * screenshot.width, rule.tapY * screenshot.height)
            } else {
                bestCoords
            }

            val isContinue = rule.id.contains("continue", ignoreCase = true)
            return FoundMatch(
                name = rule.id,
                clickX = clickX,
                clickY = clickY,
                score = bestScore,
                isContinue = isContinue,
                afterDelayMs = rule.afterDelayMs,
                rule = rule
            )
        }
        return null
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
        if (rawTemplates != null && rawTemplates!!.isNotEmpty() && rawTemplates!!.all { !it.bitmap.isRecycled }) {
            return
        }

        val templates = mutableListOf<RawTemplate>()
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
     * Предварительный расчет шаблонов в 5 масштабах: 0.85, 0.93, 1.0, 1.07, 1.18.
     * Не вызывает recycle() на исходных шаблонах rawTemplate.
     */
    private fun getPrecomputedTemplates(screenWidth: Int, screenHeight: Int): List<PrecomputedMultiScaleTemplate> {
        if (precomputedTemplates != null && cachedScreenWidth == screenWidth && cachedScreenHeight == screenHeight) {
            if (rawTemplates != null && rawTemplates!!.all { !it.bitmap.isRecycled }) {
                return precomputedTemplates!!
            }
        }

        ensureTemplatesLoaded()
        val raw = rawTemplates ?: return emptyList()
        val minDim = minOf(screenWidth, screenHeight)
        val baseScale = minDim / 800f
        val scaleMultipliers = floatArrayOf(0.85f, 0.93f, 1.0f, 1.07f, 1.18f)
        val result = mutableListOf<PrecomputedMultiScaleTemplate>()

        for (template in raw) {
            if (template.bitmap.isRecycled) continue
            val variants = mutableListOf<TemplateVariant>()
            for (m in scaleMultipliers) {
                val scale = baseScale * m
                var targetW = (template.bitmap.width * scale).roundToInt().coerceAtLeast(4)
                var targetH = (template.bitmap.height * scale).roundToInt().coerceAtLeast(4)
                if (targetW % 2 != 0) targetW++
                if (targetH % 2 != 0) targetH++

                val downW = targetW / 2
                val downH = targetH / 2

                val scaledBmp = if (targetW == template.bitmap.width && targetH == template.bitmap.height) {
                    template.bitmap
                } else {
                    Bitmap.createScaledBitmap(template.bitmap, targetW, targetH, true)
                }
                val pixels = IntArray(targetW * targetH)
                scaledBmp.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
                if (scaledBmp !== template.bitmap && !scaledBmp.isRecycled) {
                    scaledBmp.recycle()
                }

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
     * Поиск желтой кнопки «НАЧАТЬ» в лобби или желтой кнопки «ПРОДОЛЖИТЬ» по цвету и прямоугольной форме.
     */
    private fun detectYellowButton(
        screenshot: Bitmap,
        fracX0: Float,
        fracX1: Float,
        fracY0: Float,
        fracY1: Float,
        name: String,
        isContinue: Boolean
    ): FoundMatch? {
        val sW = screenshot.width
        val sH = screenshot.height
        val x0 = (sW * fracX0).toInt().coerceIn(0, sW - 1)
        val x1 = (sW * fracX1).toInt().coerceIn(x0 + 1, sW)
        val y0 = (sH * fracY0).toInt().coerceIn(0, sH - 1)
        val y1 = (sH * fracY1).toInt().coerceIn(y0 + 1, sH)
        val roiW = x1 - x0
        val roiH = y1 - y0
        if (roiW < 10 || roiH < 10) return null

        val pixels = IntArray(roiW * roiH)
        screenshot.getPixels(pixels, 0, roiW, x0, y0, roiW, roiH)

        var minX = roiW
        var maxX = 0
        var minY = roiH
        var maxY = 0
        var yellowCount = 0

        val step = 2
        for (y in 0 until roiH step step) {
            val rowOffset = y * roiW
            for (x in 0 until roiW step step) {
                val p = pixels[rowOffset + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                // Золотисто-желтый оттенок PUBG Mobile
                if (r in 180..255 && g in 130..245 && b in 0..115 && (r - b) >= 85 && (g - b) >= 45 && r >= (g - 20)) {
                    yellowCount++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (yellowCount < 15) return null

        val boxW = maxX - minX + 1
        val boxH = maxY - minY + 1

        val minAllowedW = (sW * 0.06f).toInt()
        val maxAllowedW = (sW * 0.40f).toInt()
        val minAllowedH = (sH * 0.03f).toInt()
        val maxAllowedH = (sH * 0.22f).toInt()

        if (boxW in minAllowedW..maxAllowedW && boxH in minAllowedH..maxAllowedH) {
            val aspect = boxW.toFloat() / boxH
            if (aspect in 1.4f..6.5f) {
                val totalSampled = ((boxW / step) * (boxH / step)).coerceAtLeast(1)
                val density = yellowCount.toFloat() / totalSampled
                if (density >= 0.25f) {
                    val clickX = x0 + (minX + maxX) / 2f
                    val clickY = y0 + (minY + maxY) / 2f
                    val score = (0.86f + (density * 0.10f)).coerceAtMost(0.98f)
                    return FoundMatch(name, clickX, clickY, score, isContinue)
                }
            }
        }
        return null
    }

    /**
     * Поиск синей кнопки «ПРОДОЛЖИТЬ» / «В ЛОББИ» по цвету и прямоугольной форме.
     */
    private fun detectBlueButton(
        screenshot: Bitmap,
        fracX0: Float,
        fracX1: Float,
        fracY0: Float,
        fracY1: Float,
        name: String,
        isContinue: Boolean
    ): FoundMatch? {
        val sW = screenshot.width
        val sH = screenshot.height
        val x0 = (sW * fracX0).toInt().coerceIn(0, sW - 1)
        val x1 = (sW * fracX1).toInt().coerceIn(x0 + 1, sW)
        val y0 = (sH * fracY0).toInt().coerceIn(0, sH - 1)
        val y1 = (sH * fracY1).toInt().coerceIn(y0 + 1, sH)
        val roiW = x1 - x0
        val roiH = y1 - y0
        if (roiW < 10 || roiH < 10) return null

        val pixels = IntArray(roiW * roiH)
        screenshot.getPixels(pixels, 0, roiW, x0, y0, roiW, roiH)

        var minX = roiW
        var maxX = 0
        var minY = roiH
        var maxY = 0
        var blueCount = 0

        val step = 2
        for (y in 0 until roiH step step) {
            val rowOffset = y * roiW
            for (x in 0 until roiW step step) {
                val p = pixels[rowOffset + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                // Синий оттенок кнопки продолжения PUBG
                if (b in 150..255 && r in 0..120 && g in 80..230 && (b - r) >= 60) {
                    blueCount++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (blueCount < 15) return null

        val boxW = maxX - minX + 1
        val boxH = maxY - minY + 1

        val minAllowedW = (sW * 0.05f).toInt()
        val maxAllowedW = (sW * 0.35f).toInt()
        val minAllowedH = (sH * 0.03f).toInt()
        val maxAllowedH = (sH * 0.20f).toInt()

        if (boxW in minAllowedW..maxAllowedW && boxH in minAllowedH..maxAllowedH) {
            val aspect = boxW.toFloat() / boxH
            if (aspect in 1.4f..6.0f) {
                val totalSampled = ((boxW / step) * (boxH / step)).coerceAtLeast(1)
                val density = blueCount.toFloat() / totalSampled
                if (density >= 0.25f) {
                    val clickX = x0 + (minX + maxX) / 2f
                    val clickY = y0 + (minY + maxY) / 2f
                    val score = (0.84f + (density * 0.10f)).coerceAtMost(0.96f)
                    return FoundMatch(name, clickX, clickY, score, isContinue)
                }
            }
        }
        return null
    }

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
     * Комплексное сканирование всех кандидатов:
     * 1. «НАЧАТЬ» в лобби (левый нижний угол: цвет + шаблон)
     * 2. «ПРОДОЛЖИТЬ» после катки (правый нижний угол: шаблоны continue_mvp, continue_blue + желтый/синий цвет)
     */
    private fun scanAllCandidates(screenshot: Bitmap): CandidateScanResult {
        val templates = getPrecomputedTemplates(screenshot.width, screenshot.height)
        val tmplMvp = templates.firstOrNull { it.name == "continue_mvp" }
        val tmplBlue = templates.firstOrNull { it.name == "continue_blue" }
        val tmplStart = templates.firstOrNull { it.name == "start" }

        var countCandidates = 0

        // 1. Поиск в левом нижнем углу («НАЧАТЬ»)
        var bestStartMatch: FoundMatch? = null
        var bestStartScore = 0f

        // А) Поиск жёлтой кнопки «НАЧАТЬ» по цвету и геометрии
        val yellowStart = detectYellowButton(screenshot, 0.0f, 0.42f, 0.65f, 1.0f, "start_yellow", false)
        if (yellowStart != null && yellowStart.score >= 0.78f) {
            bestStartMatch = yellowStart
            bestStartScore = yellowStart.score
            countCandidates++
        }

        // Б) Шаблонный поиск «НАЧАТЬ»
        var tmplStartScore = 0f
        val startRoi = createDownsampledRoi(screenshot, 0.0f, 0.42f, 0.65f, 1.0f)
        if (startRoi != null && tmplStart != null) {
            for (v in tmplStart.variants) {
                val (score, coords) = searchTemplateInRoi(startRoi, v)
                if (score > tmplStartScore) {
                    tmplStartScore = score
                    if (score >= 0.75f && score > bestStartScore) {
                        bestStartScore = score
                        bestStartMatch = FoundMatch("start_template", coords.first, coords.second, score, false)
                        countCandidates++
                    }
                }
            }
        }

        // 2. Поиск в правом нижнем углу («ПРОДОЛЖИТЬ»)
        var bestContinueMatch: FoundMatch? = null
        var bestContinueScore = 0f

        // А) Шаблоны continue_mvp и continue_blue
        var scoreMvp = 0f
        var scoreBlueTmpl = 0f
        val continueRoi = createDownsampledRoi(screenshot, 0.50f, 1.0f, 0.65f, 1.0f)
        if (continueRoi != null) {
            if (tmplMvp != null) {
                for (v in tmplMvp.variants) {
                    val (score, coords) = searchTemplateInRoi(continueRoi, v)
                    if (score > scoreMvp) {
                        scoreMvp = score
                        if (score >= 0.75f && score > bestContinueScore) {
                            bestContinueScore = score
                            bestContinueMatch = FoundMatch("continue_mvp", coords.first, coords.second, score, true)
                            countCandidates++
                        }
                    }
                }
            }
            if (tmplBlue != null) {
                for (v in tmplBlue.variants) {
                    val (score, coords) = searchTemplateInRoi(continueRoi, v)
                    if (score > scoreBlueTmpl) {
                        scoreBlueTmpl = score
                        if (score >= 0.75f && score > bestContinueScore) {
                            bestContinueScore = score
                            bestContinueMatch = FoundMatch("continue_blue", coords.first, coords.second, score, true)
                            countCandidates++
                        }
                    }
                }
            }
        }

        // Б) Цветовой поиск продолжения (синяя или желтая кнопка)
        val blueContinue = detectBlueButton(screenshot, 0.50f, 1.0f, 0.65f, 1.0f, "continue_blue_color", true)
        if (blueContinue != null && blueContinue.score >= 0.78f) {
            countCandidates++
            if (blueContinue.score > bestContinueScore) {
                bestContinueScore = blueContinue.score
                bestContinueMatch = blueContinue
            }
        }

        val yellowContinue = detectYellowButton(screenshot, 0.50f, 1.0f, 0.65f, 1.0f, "continue_yellow_color", true)
        if (yellowContinue != null && yellowContinue.score >= 0.78f) {
            countCandidates++
            if (yellowContinue.score > bestContinueScore) {
                bestContinueScore = yellowContinue.score
                bestContinueMatch = yellowContinue
            }
        }

        val summary = String.format(
            Locale.US,
            "start(color=%.2f, tmpl=%.2f), continue(mvp=%.2f, blue=%.2f, col_blue=%.2f, col_yel=%.2f)",
            yellowStart?.score ?: 0f,
            tmplStartScore,
            scoreMvp,
            scoreBlueTmpl,
            blueContinue?.score ?: 0f,
            yellowContinue?.score ?: 0f
        )

        return CandidateScanResult(
            bestStart = bestStartMatch,
            bestContinue = bestContinueMatch,
            totalCandidates = countCandidates,
            debugSummary = summary
        )
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
