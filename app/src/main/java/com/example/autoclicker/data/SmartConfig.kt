package com.example.autoclicker.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Режим работы умного режима.
 */
enum class SmartConfigMode {
    DEFAULT_ONLY, // Только встроенные правила (PUBG)
    CUSTOM_ONLY,  // Только выбранный пользовательский конфиг
    MERGED        // Встроенный + пользовательский (с заменой по id)
}

/**
 * Одно правило обнаружения и действия для Smart Mode.
 */
data class SmartRule(
    val id: String,
    val priority: Int,
    val imageFile: String? = null,
    val imageSource: String? = null,
    val imageBox: FloatArray? = null, // [left, top, right, bottom] в долях 0..1
    val region: FloatArray? = null,   // [left, top, right, bottom] в долях 0..1
    val threshold: Float = 0.80f,
    val tapTarget: String = "found",  // "found" или "fixed"
    val tapX: Float? = null,
    val tapY: Float? = null,
    val afterDelayMs: Long = 3000L,
    val refHeight: Int = 800,
    val builtin: Boolean = false,
    val configDir: File? = null
) {
    /**
     * Возвращает эффективную область поиска:
     * - Если region задан: region;
     * - Если region не задан и есть source + box: box, расширенный на 25% в каждую сторону;
     * - Если region не задан и указан file: весь экран [0, 0, 1, 1].
     */
    fun getEffectiveRegion(): FloatArray {
        if (region != null && region.size == 4) {
            return region
        }
        if (imageBox != null && imageBox.size == 4) {
            val bL = imageBox[0]
            val bT = imageBox[1]
            val bR = imageBox[2]
            val bB = imageBox[3]
            val w = bR - bL
            val h = bB - bT
            val padX = w * 0.25f
            val padY = h * 0.25f
            return floatArrayOf(
                (bL - padX).coerceIn(0f, 1f),
                (bT - padY).coerceIn(0f, 1f),
                (bR + padX).coerceIn(0f, 1f),
                (bB + padY).coerceIn(0f, 1f)
            )
        }
        return floatArrayOf(0.0f, 0.0f, 1.0f, 1.0f)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SmartRule
        if (id != other.id) return false
        if (priority != other.priority) return false
        if (imageFile != other.imageFile) return false
        if (imageSource != other.imageSource) return false
        if (imageBox != null) {
            if (other.imageBox == null || !imageBox.contentEquals(other.imageBox)) return false
        } else if (other.imageBox != null) return false
        if (region != null) {
            if (other.region == null || !region.contentEquals(other.region)) return false
        } else if (other.region != null) return false
        if (threshold != other.threshold) return false
        if (tapTarget != other.tapTarget) return false
        if (tapX != other.tapX) return false
        if (tapY != other.tapY) return false
        if (afterDelayMs != other.afterDelayMs) return false
        if (refHeight != other.refHeight) return false
        if (builtin != other.builtin) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + priority
        result = 31 * result + (imageFile?.hashCode() ?: 0)
        result = 31 * result + (imageSource?.hashCode() ?: 0)
        result = 31 * result + (imageBox?.contentHashCode() ?: 0)
        result = 31 * result + (region?.contentHashCode() ?: 0)
        result = 31 * result + threshold.hashCode()
        result = 31 * result + tapTarget.hashCode()
        result = 31 * result + (tapX?.hashCode() ?: 0)
        result = 31 * result + (tapY?.hashCode() ?: 0)
        result = 31 * result + afterDelayMs.hashCode()
        result = 31 * result + refHeight
        result = 31 * result + builtin.hashCode()
        return result
    }
}

/**
 * Конфиг правил умного режима.
 */
data class SmartConfig(
    val name: String,
    val version: Int,
    val refWidth: Int,
    val refHeight: Int,
    val scanIntervalMs: Long = 2000L,
    val idleTimeoutMin: Int = 5,
    val rules: List<SmartRule>
) {
    companion object {
        /**
         * Встроенный конфиг "default" (PUBG). Нельзя удалить или изменить.
         */
        fun createDefaultConfig(): SmartConfig {
            val rules = listOf(
                SmartRule(
                    id = "continue_mvp",
                    priority = 1,
                    imageFile = "templates/continue_mvp.png",
                    region = floatArrayOf(0.50f, 0.65f, 1.0f, 1.0f),
                    threshold = 0.75f,
                    tapTarget = "found",
                    afterDelayMs = 3000L,
                    refHeight = 800,
                    builtin = true
                ),
                SmartRule(
                    id = "continue_blue",
                    priority = 2,
                    imageFile = "templates/continue_blue.png",
                    region = floatArrayOf(0.50f, 0.65f, 1.0f, 1.0f),
                    threshold = 0.75f,
                    tapTarget = "found",
                    afterDelayMs = 3000L,
                    refHeight = 800,
                    builtin = true
                ),
                SmartRule(
                    id = "start",
                    priority = 3,
                    imageFile = "templates/start.png",
                    region = floatArrayOf(0.0f, 0.42f, 0.65f, 1.0f),
                    threshold = 0.75f,
                    tapTarget = "found",
                    afterDelayMs = 10000L,
                    refHeight = 800,
                    builtin = true
                )
            )

            return SmartConfig(
                name = "default",
                version = 1,
                refWidth = 1340,
                refHeight = 800,
                scanIntervalMs = 2000L,
                idleTimeoutMin = 5,
                rules = rules
            )
        }

        /**
         * Парсинг и строгая валидация JSON конфига.
         */
        fun fromJson(jsonStr: String, configDir: File? = null): SmartConfig {
            val json = JSONObject(jsonStr)
            val name = json.optString("name", "").trim()
            if (name.isEmpty()) {
                throw IllegalArgumentException("Поле 'name' не может быть пустым")
            }
            val version = json.optInt("version", 1)
            val refWidth = json.optInt("refWidth", 1340).coerceAtLeast(100)
            val refHeight = json.optInt("refHeight", 800).coerceAtLeast(100)
            val scanIntervalMs = json.optLong("scanIntervalMs", 2000L).coerceIn(500L, 10000L)
            val idleTimeoutMin = json.optInt("idleTimeoutMin", 5).coerceIn(1, 60)

            val rulesArr = json.optJSONArray("rules")
                ?: throw IllegalArgumentException("Отсутствует массив 'rules'")
            if (rulesArr.length() == 0) {
                throw IllegalArgumentException("Массив 'rules' не должен быть пустым")
            }

            val rulesList = mutableListOf<SmartRule>()
            for (i in 0 until rulesArr.length()) {
                val rObj = rulesArr.getJSONObject(i)
                val id = rObj.optString("id", "").trim()
                if (id.isEmpty()) {
                    throw IllegalArgumentException("Правило #${i + 1}: 'id' не может быть пустым")
                }
                val priority = rObj.optInt("priority", i + 1)

                // Проверка image
                if (!rObj.has("image")) {
                    throw IllegalArgumentException("Правило '$id': отсутствует блок 'image'")
                }
                val imgObj = rObj.getJSONObject("image")
                val imgFile = if (imgObj.has("file")) imgObj.getString("file").trim() else null
                val imgSrc = if (imgObj.has("source")) imgObj.getString("source").trim() else null

                var boxArray: FloatArray? = null
                if (imgObj.has("box")) {
                    val bArr = imgObj.getJSONArray("box")
                    if (bArr.length() != 4) {
                        throw IllegalArgumentException("Правило '$id': image.box должен содержать 4 координаты")
                    }
                    val l = bArr.getDouble(0).toFloat()
                    val t = bArr.getDouble(1).toFloat()
                    val r = bArr.getDouble(2).toFloat()
                    val b = bArr.getDouble(3).toFloat()
                    validateFractions("Правило '$id': image.box", l, t, r, b)
                    if (l >= r || t >= b) {
                        throw IllegalArgumentException("Правило '$id': image.box некорректен (левый < правый, верхний < нижний)")
                    }
                    boxArray = floatArrayOf(l, t, r, b)
                }

                if (imgFile.isNullOrEmpty() && (imgSrc.isNullOrEmpty() || boxArray == null)) {
                    throw IllegalArgumentException("Правило '$id': в image должно быть указано либо 'file', либо 'source' с 'box'")
                }

                // Проверка region (если задан)
                var regionArray: FloatArray? = null
                if (rObj.has("region") && !rObj.isNull("region")) {
                    val regArr = rObj.getJSONArray("region")
                    if (regArr.length() != 4) {
                        throw IllegalArgumentException("Правило '$id': region должен содержать 4 координаты")
                    }
                    val l = regArr.getDouble(0).toFloat()
                    val t = regArr.getDouble(1).toFloat()
                    val r = regArr.getDouble(2).toFloat()
                    val b = regArr.getDouble(3).toFloat()
                    validateFractions("Правило '$id': region", l, t, r, b)
                    if (l >= r || t >= b) {
                        throw IllegalArgumentException("Правило '$id': region некорректен (левый < правый, верхний < нижний)")
                    }
                    regionArray = floatArrayOf(l, t, r, b)
                }

                val threshold = rObj.optDouble("threshold", 0.80).toFloat().coerceIn(0.50f, 0.99f)

                // Проверка tap
                if (!rObj.has("tap")) {
                    throw IllegalArgumentException("Правило '$id': отсутствует блок 'tap'")
                }
                val tapObj = rObj.getJSONObject("tap")
                val target = tapObj.optString("target", "found").trim().lowercase()
                if (target != "found" && target != "fixed") {
                    throw IllegalArgumentException("Правило '$id': tap.target должен быть 'found' или 'fixed'")
                }
                var tapX: Float? = null
                var tapY: Float? = null
                if (target == "fixed") {
                    if (!tapObj.has("x") || !tapObj.has("y")) {
                        throw IllegalArgumentException("Правило '$id': для tap.target='fixed' обязательны 'x' и 'y'")
                    }
                    val tx = tapObj.getDouble("x").toFloat()
                    val ty = tapObj.getDouble("y").toFloat()
                    validateFractions("Правило '$id': tap", tx, ty)
                    tapX = tx
                    tapY = ty
                }

                val afterDelayMs = rObj.optLong("afterDelayMs", 3000L).coerceAtLeast(100L)

                rulesList.add(
                    SmartRule(
                        id = id,
                        priority = priority,
                        imageFile = imgFile,
                        imageSource = imgSrc,
                        imageBox = boxArray,
                        region = regionArray,
                        threshold = threshold,
                        tapTarget = target,
                        tapX = tapX,
                        tapY = tapY,
                        afterDelayMs = afterDelayMs,
                        refHeight = refHeight,
                        builtin = false,
                        configDir = configDir
                    )
                )
            }

            return SmartConfig(
                name = name,
                version = version,
                refWidth = refWidth,
                refHeight = refHeight,
                scanIntervalMs = scanIntervalMs,
                idleTimeoutMin = idleTimeoutMin,
                rules = rulesList
            )
        }

        private fun validateFractions(label: String, vararg values: Float) {
            for (v in values) {
                if (v < 0f || v > 1f) {
                    throw IllegalArgumentException("$label: все координаты должны быть в долях 0..1 (получено $v)")
                }
            }
        }
    }
}
