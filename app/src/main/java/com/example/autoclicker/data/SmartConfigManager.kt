package com.example.autoclicker.data

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

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
    fun buildEffectiveRules(): EffectiveConfig {
        val mode = getMode()
        val activeName = getActiveName()
        val defaultConfig = SmartConfig.createDefaultConfig()
        val customConfig = loadCustomConfig(activeName)

        val isCustomValid = customConfig != null && customConfig.rules.isNotEmpty()

        if ((mode == SmartConfigMode.CUSTOM_ONLY || mode == SmartConfigMode.MERGED) && !isCustomValid) {
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
     * Безопасный импорт zip архива с конфигом.
     */
    fun importZip(uri: Uri): Result<String> {
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

            val allowedExtensions = setOf("json", "png", "jpg", "jpeg")
            val buffer = ByteArray(8192)

            ZipInputStream(inputStream).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    fileCount++
                    if (fileCount > maxFiles) {
                        return Result.failure(Exception("В архиве слишком много файлов (максимум $maxFiles)"))
                    }

                    val entryName = entry.name.replace('\\', '/')
                    // Пропускаем директории или файлы macOS metadata (__MACOSX)
                    if (!entry.isDirectory && !entryName.contains("__MACOSX") && !entryName.startsWith(".")) {
                        val simpleFileName = File(entryName).name
                        val ext = simpleFileName.substringAfterLast('.', "").lowercase()

                        if (ext !in allowedExtensions) {
                            return Result.failure(Exception("Архив содержит недопустимый файл '$simpleFileName'. Разрешены только .json, .png, .jpg"))
                        }

                        val targetFile = File(tempExtractDir, simpleFileName)
                        val canonicalTarget = targetFile.canonicalPath

                        // Защита от Zip Slip
                        if (!canonicalTarget.startsWith(canonicalDestDir)) {
                            return Result.failure(Exception("Обнаружена угроза безопасности пути (Zip Slip)"))
                        }

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

            // Проверка наличия config.json
            val configFile = File(tempExtractDir, "config.json")
            if (!configFile.exists()) {
                return Result.failure(Exception("В архиве отсутствует обязательный файл 'config.json'"))
            }

            // Валидация JSON и правил
            val jsonStr = configFile.readText(Charsets.UTF_8)
            val config = try {
                SmartConfig.fromJson(jsonStr, tempExtractDir)
            } catch (e: Exception) {
                return Result.failure(Exception("Ошибка в файле config.json: ${e.message}"))
            }

            // Проверка наличия всех упомянутых картинок
            for (rule in config.rules) {
                if (!rule.imageFile.isNullOrEmpty()) {
                    val imgFile = File(tempExtractDir, File(rule.imageFile).name)
                    if (!imgFile.exists()) {
                        return Result.failure(Exception("В архиве отсутствует файл изображения '${rule.imageFile}' для правила '${rule.id}'"))
                    }
                }
                if (!rule.imageSource.isNullOrEmpty()) {
                    val srcFile = File(tempExtractDir, File(rule.imageSource).name)
                    if (!srcFile.exists()) {
                        return Result.failure(Exception("В архиве отсутствует файл исходного скриншота '${rule.imageSource}' для правила '${rule.id}'"))
                    }
                }
            }

            // Перемещение в постоянную папку filesDir/configs/<name>
            val safeFolderName = config.name.replace(Regex("[^a-zA-Z0-9а-яА-Я._-]"), "_")
            val destFolder = File(configsDir, safeFolderName)
            if (destFolder.exists()) {
                destFolder.deleteRecursively()
            }
            if (!destFolder.mkdirs()) {
                return Result.failure(Exception("Не удалось создать папку для сохранения конфига"))
            }

            tempExtractDir.listFiles()?.forEach { file ->
                file.copyTo(File(destFolder, file.name), overwrite = true)
            }

            return Result.success(safeFolderName)

        } catch (e: Exception) {
            return Result.failure(Exception("Ошибка распаковки архива: ${e.message}"))
        } finally {
            try {
                inputStream?.close()
            } catch (_: Exception) {}
            tempExtractDir?.deleteRecursively()
        }
    }

    companion object {
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
