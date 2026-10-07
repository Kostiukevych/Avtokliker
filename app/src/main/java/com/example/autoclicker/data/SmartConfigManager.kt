package com.example.autoclicker.data

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Отчёт об импорте zip архива.
 */
data class ImportReport(
    val configName: String,
    val rulesCount: Int,
    val templatesPrepared: Int,
    val warnings: List<String>,
    val problems: List<String>
)

/**
 * Итоговая конфигурация правил для работы SmartEngine.
 */
data class EffectiveConfig(
    val rules: List<SmartRule>,
    val scanIntervalMs: Long,
    val idleTimeoutMin: Int,
    val mode: SmartConfigMode,
    val customConfigName: String?,
    val builtinCount: Int,
    val customCount: Int
)

/**
 * Менеджер пользовательских и встроенных конфигов для Smart Mode.
 */
class SmartConfigManager private constructor(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val configsDir = File(context.filesDir, "configs")
    private val mainHandler = Handler(Looper.getMainLooper())

    var onConfigChangedListener: (() -> Unit)? = null

    init {
        if (!configsDir.exists()) {
            configsDir.mkdirs()
        }
    }

    fun listConfigs(): List<String> {
        val dirs = configsDir.listFiles { file ->
            file.isDirectory && File(file, "config.json").exists()
        } ?: return emptyList()
        return dirs.map { it.name }.sorted()
    }

    fun getMode(): SmartConfigMode {
        val modeStr = prefs.getString(KEY_SMART_CONFIG_MODE, SmartConfigMode.DEFAULT_ONLY.name)
        return try {
            SmartConfigMode.valueOf(modeStr ?: SmartConfigMode.DEFAULT_ONLY.name)
        } catch (_: Exception) {
            SmartConfigMode.DEFAULT_ONLY
        }
    }

    fun setMode(mode: SmartConfigMode) {
        prefs.edit().putString(KEY_SMART_CONFIG_MODE, mode.name).apply()
        onConfigChangedListener?.invoke()
    }

    fun getActiveName(): String {
        return prefs.getString(KEY_ACTIVE_CONFIG, "") ?: ""
    }

    fun setActiveName(name: String) {
        prefs.edit().putString(KEY_ACTIVE_CONFIG, name).apply()
        onConfigChangedListener?.invoke()
    }

    fun delete(name: String): Boolean {
        val targetDir = File(configsDir, name)
        if (!targetDir.exists()) return false

        val deleted = targetDir.deleteRecursively()
        if (deleted && getActiveName() == name) {
            setActiveName("")
        }
        return deleted
    }

    fun loadCustomConfig(name: String): SmartConfig? {
        if (name.isBlank()) return null
        val dir = File(configsDir, name)
        val configFile = File(dir, "config.json")
        if (!configFile.exists()) return null

        return try {
            val jsonStr = configFile.readText(Charsets.UTF_8)
            SmartConfig.fromJson(jsonStr, dir)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Построение списка эффективных правил для SmartEngine.
     */
    fun buildEffectiveRules(silent: Boolean = false): EffectiveConfig {
        val mode = getMode()
        val activeName = getActiveName()
        val defaultConfig = SmartConfig.createDefaultConfig()
        val customConfig = loadCustomConfig(activeName)

        val isCustomValid = customConfig != null && customConfig.rules.isNotEmpty()

        if ((mode == SmartConfigMode.CUSTOM_ONLY || mode == SmartConfigMode.MERGED) && !isCustomValid) {
            if (!silent) {
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "SMART: свой конфиг не выбран, работает встроенный",
                    isError = false
                )
                mainHandler.post {
                    Toast.makeText(
                        context,
                        "SMART: свой конфиг не выбран, работает встроенный",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            return EffectiveConfig(
                rules = defaultConfig.rules,
                scanIntervalMs = defaultConfig.scanIntervalMs,
                idleTimeoutMin = defaultConfig.idleTimeoutMin,
                mode = SmartConfigMode.DEFAULT_ONLY,
                customConfigName = null,
                builtinCount = defaultConfig.rules.size,
                customCount = 0
            )
        }

        return when (mode) {
            SmartConfigMode.DEFAULT_ONLY -> {
                EffectiveConfig(
                    rules = defaultConfig.rules,
                    scanIntervalMs = defaultConfig.scanIntervalMs,
                    idleTimeoutMin = defaultConfig.idleTimeoutMin,
                    mode = SmartConfigMode.DEFAULT_ONLY,
                    customConfigName = null,
                    builtinCount = defaultConfig.rules.size,
                    customCount = 0
                )
            }

            SmartConfigMode.CUSTOM_ONLY -> {
                val rules = customConfig!!.rules
                EffectiveConfig(
                    rules = rules.sortedBy { it.priority },
                    scanIntervalMs = customConfig.scanIntervalMs,
                    idleTimeoutMin = customConfig.idleTimeoutMin,
                    mode = SmartConfigMode.CUSTOM_ONLY,
                    customConfigName = customConfig.name,
                    builtinCount = 0,
                    customCount = rules.size
                )
            }

            SmartConfigMode.MERGED -> {
                val customMap = customConfig!!.rules.associateBy { it.id }
                val mergedList = mutableListOf<SmartRule>()

                // Пользовательские правила заменяют встроенные при совпадении id
                for (bRule in defaultConfig.rules) {
                    if (customMap.containsKey(bRule.id)) {
                        mergedList.add(customMap[bRule.id]!!)
                    } else {
                        mergedList.add(bRule)
                    }
                }

                // Добавляем новые пользовательские правила (которых не было во встроенных)
                val defaultIds = defaultConfig.rules.map { it.id }.toSet()
                for (cRule in customConfig.rules) {
                    if (!defaultIds.contains(cRule.id)) {
                        mergedList.add(cRule)
                    }
                }

                // Сортировка по возрастанию priority (при равном priority пользовательское раньше)
                val sortedRules = mergedList.sortedWith(
                    Comparator { r1, r2 ->
                        val pDiff = r1.priority.compareTo(r2.priority)
                        if (pDiff != 0) pDiff
                        else {
                            // Если приоритеты равны, пользовательское (builtin = false) раньше
                            when {
                                !r1.builtin && r2.builtin -> -1
                                r1.builtin && !r2.builtin -> 1
                                else -> 0
                            }
                        }
                    }
                )

                val builtinCount = sortedRules.count { it.builtin }
                val customCount = sortedRules.count { !it.builtin }

                EffectiveConfig(
                    rules = sortedRules,
                    scanIntervalMs = customConfig.scanIntervalMs,
                    idleTimeoutMin = customConfig.idleTimeoutMin,
                    mode = SmartConfigMode.MERGED,
                    customConfigName = customConfig.name,
                    builtinCount = builtinCount,
                    customCount = customCount
                )
            }
        }
    }

    /**
     * Описание активного конфига словами для UI.
     */
    fun describeActive(): String {
        val eff = buildEffectiveRules(silent = true)
        val modeStr = when (eff.mode) {
            SmartConfigMode.DEFAULT_ONLY -> "Встроенный"
            SmartConfigMode.CUSTOM_ONLY -> "Только мой"
            SmartConfigMode.MERGED -> "Встроенный + мой"
        }
        val namePart = if (!eff.customConfigName.isNullOrBlank()) " ${eff.customConfigName}" else ""
        return "$modeStr$namePart, правил ${eff.rules.size} (встроенных ${eff.builtinCount}, своих ${eff.customCount})"
    }

    /**
     * Безопасный импорт zip архива с конфигом с возвратом подробного отчёта.
     */
    fun importZipWithReport(uri: Uri): Result<ImportReport> {
        var inputStream: InputStream? = null
        var tempExtractDir: File? = null

        try {
            inputStream = context.contentResolver.openInputStream(uri)
                ?: return Result.failure(Exception("Не удалось открыть выбранный файл архива"))

            tempExtractDir = File(context.cacheDir, "zip_temp_${System.currentTimeMillis()}")
            if (!tempExtractDir.mkdirs()) {
                return Result.failure(Exception("Не удалось создать временную папку для распаковки"))
            }

            val canonicalDestDir = tempExtractDir.canonicalPath
            var fileCount = 0
            var totalUncompressedBytes = 0L
            val maxFiles = 50
            val maxBytes = 20 * 1024 * 1024L // 20 MB

            val buffer = ByteArray(8192)

            ZipInputStream(inputStream).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val rawName = entry.name.replace('\\', '/')
                    val segments = rawName.split('/')
                    val simpleFileName = segments.lastOrNull().orEmpty()

                    // Пропускаем директории или файлы macOS metadata (__MACOSX) и скрытые файлы
                    if (rawName.contains("__MACOSX") || simpleFileName.startsWith(".")) {
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }

                    if (entry.isDirectory) {
                        val dir = File(tempExtractDir, rawName)
                        if (!dir.canonicalPath.startsWith(canonicalDestDir)) {
                            return Result.failure(Exception("Обнаружена угроза безопасности пути (Zip Slip)"))
                        }
                        dir.mkdirs()
                    } else {
                        fileCount++
                        if (fileCount > maxFiles) {
                            return Result.failure(Exception("В архиве слишком много файлов (максимум $maxFiles)"))
                        }

                        val ext = simpleFileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
                        if (ext !in SmartImageLoader.ALLOWED_EXTENSIONS) {
                            return Result.failure(
                                Exception("Архив содержит недопустимый файл '$simpleFileName'. Разрешены: .json, .png, .jpg, .jpeg, .webp, .bmp, .gif, .heic, .heif")
                            )
                        }

                        val targetFile = File(tempExtractDir, rawName)
                        val canonicalTarget = targetFile.canonicalPath

                        // Защита от Zip Slip
                        if (!canonicalTarget.startsWith(canonicalDestDir)) {
                            return Result.failure(Exception("Обнаружена угроза безопасности пути (Zip Slip)"))
                        }

                        targetFile.parentFile?.mkdirs()
                        FileOutputStream(targetFile).use { fos ->
                            var read: Int
                            while (zis.read(buffer).also { read = it } != -1) {
                                totalUncompressedBytes += read
                                if (totalUncompressedBytes > maxBytes) {
                                    return Result.failure(Exception("Размер файлов в архиве превышает лимит 20 МБ"))
                                }
                                fos.write(buffer, 0, read)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            // Определение корня конфига: в корне или в единственной подпапке
            val directConfigFile = File(tempExtractDir, "config.json")
            val rootDir: File = if (directConfigFile.exists()) {
                tempExtractDir
            } else {
                val topLevelDirs = tempExtractDir.listFiles { f ->
                    f.isDirectory && !f.name.startsWith(".") && f.name != "__MACOSX"
                } ?: emptyArray()

                if (topLevelDirs.size == 1 && File(topLevelDirs[0], "config.json").exists()) {
                    topLevelDirs[0]
                } else {
                    return Result.failure(Exception("В архиве отсутствует обязательный файл 'config.json'"))
                }
            }

            val configFile = File(rootDir, "config.json")
            val jsonStr = configFile.readText(Charsets.UTF_8)
            val warnings = mutableListOf<String>()
            val problems = mutableListOf<String>()

            val (cleanJson, hasTrailing) = SmartConfig.extractRootJsonObject(jsonStr)
            if (hasTrailing) {
                warnings.add("В config.json обнаружен и обрезан посторонний текст после JSON")
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "SMART: предупреждение: в config.json обнаружен и обрезан посторонний текст после основного объекта JSON"
                )
            }

            val config = try {
                SmartConfig.fromJson(cleanJson, rootDir)
            } catch (e: Exception) {
                return Result.failure(Exception("Ошибка в файле config.json: ${e.message}"))
            }

            // Проверка картинок для ВСЕХ элементов rule.allImages во временной папке rootDir ДО изменения destFolder
            var templatesPrepared = 0
            var validRulesCount = 0

            for (rule in config.rules) {
                var ruleHasUsable = false
                // Свайп без картинок — валидное правило
                if (rule.action == "swipe" && rule.allImages.isEmpty()
                    && rule.swipeFromX != null && rule.swipeToX != null
                ) {
                    ruleHasUsable = true
                    templatesPrepared++ // условно: жест настроен
                }
                for (img in rule.allImages) {
                    val targetFileName = if (!img.file.isNullOrEmpty()) {
                        File(img.file).name
                    } else if (!img.source.isNullOrEmpty()) {
                        File(img.source).name
                    } else {
                        null
                    }

                    if (targetFileName == null) continue

                    val diskFile = SmartImageLoader.resolve(rootDir, targetFileName)
                    if (diskFile == null || !diskFile.exists()) {
                        return Result.failure(
                            Exception("В архиве отсутствует файл изображения '$targetFileName' для правила '${rule.id}'")
                        )
                    }

                    val decodeRes = SmartImageLoader.decode(diskFile)
                    if (decodeRes.isFailure) {
                        problems.add(decodeRes.exceptionOrNull()?.message ?: "Не удалось прочитать картинку $targetFileName")
                        continue
                    }

                    val decoded = decodeRes.getOrNull()
                    val bmp = decoded?.bitmap
                    if (bmp == null) {
                        problems.add("Не удалось прочитать картинку $targetFileName")
                        continue
                    }

                    try {
                        if (img.source != null && img.box != null) {
                            val cropped = SmartImageLoader.cropByBox(bmp, img.box)
                            if (cropped == null) {
                                problems.add("Не удалось вырезать шаблон для правила '${rule.id}' из $targetFileName")
                            } else {
                                try {
                                    if (SmartImageLoader.isFlat(cropped)) {
                                        problems.add("шаблон ${rule.id} слишком однотонный")
                                    } else {
                                        templatesPrepared++
                                        ruleHasUsable = true
                                    }
                                } finally {
                                    if (cropped !== bmp) {
                                        cropped.recycle()
                                    }
                                }
                            }
                        } else {
                            if (SmartImageLoader.isFlat(bmp)) {
                                problems.add("шаблон ${rule.id} слишком однотонный")
                            } else {
                                templatesPrepared++
                                ruleHasUsable = true
                            }
                        }
                    } finally {
                        bmp.recycle()
                    }
                }
                if (ruleHasUsable) {
                    validRulesCount++
                }
            }

            if (config.rules.isEmpty()) {
                return Result.failure(Exception("В файле config.json отсутствуют правила"))
            }

            // Проверка лимита профилей (MAX_CONFIGS = 5) до записи в destFolder
            val safeFolderName = config.name.replace(Regex("[^a-zA-Z0-9а-яА-Я._-]"), "_")
            val destFolder = File(configsDir, safeFolderName)
            if (!destFolder.exists() && listConfigs().size >= MAX_CONFIGS) {
                return Result.failure(Exception("Достигнут лимит: 5 конфигов. Удалите один из загруженных и повторите"))
            }

            // Перемещение в постоянную папку filesDir/configs/<name> ПЛОСКО
            if (destFolder.exists()) {
                destFolder.deleteRecursively()
            }
            if (!destFolder.mkdirs()) {
                return Result.failure(Exception("Не удалось создать папку для сохранения конфига"))
            }

            val copiedNames = mutableSetOf<String>()
            rootDir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") && it.name != "config.json" }.forEach { sourceFile ->
                val fileName = sourceFile.name
                val existingResolved = SmartImageLoader.resolve(destFolder, fileName)
                if (existingResolved != null) {
                    warnings.add("Файл '$fileName' уже существует, копия из '${sourceFile.relativeTo(rootDir).path}' пропущена")
                } else {
                    copiedNames.add(fileName)
                    sourceFile.copyTo(File(destFolder, fileName), overwrite = true)
                }
            }

            // Записываем очищенный JSON (только основной объект)
            File(destFolder, "config.json").writeText(cleanJson, Charsets.UTF_8)

            val report = ImportReport(
                configName = safeFolderName,
                rulesCount = config.rules.size,
                templatesPrepared = templatesPrepared,
                warnings = warnings,
                problems = problems
            )
            return Result.success(report)

        } catch (e: Exception) {
            return Result.failure(Exception("Ошибка распаковки архива: ${e.message}"))
        } finally {
            try {
                inputStream?.close()
            } catch (_: Exception) {}
            tempExtractDir?.deleteRecursively()
        }
    }

    /**
     * Безопасный импорт zip архива с конфигом (совместимый метод).
     */
    fun importZip(uri: Uri): Result<String> {
        return importZipWithReport(uri).map { it.configName }
    }

    companion object {
        const val MAX_CONFIGS = 5
        private const val PREFS_NAME = "auto_clicker_prefs"
        private const val KEY_SMART_CONFIG_MODE = "smart_config_mode"
        private const val KEY_ACTIVE_CONFIG = "active_config"

        @Volatile
        private var INSTANCE: SmartConfigManager? = null

        fun getInstance(context: Context): SmartConfigManager {
            val appCtx = context.applicationContext
            val current = INSTANCE
            if (current != null && current.context === appCtx) {
                return current
            }
            return synchronized(this) {
                val inst = INSTANCE
                if (inst != null && inst.context === appCtx) {
                    inst
                } else {
                    SmartConfigManager(appCtx).also { INSTANCE = it }
                }
            }
        }

        fun resetForTesting() {
            INSTANCE = null
        }
    }
}
