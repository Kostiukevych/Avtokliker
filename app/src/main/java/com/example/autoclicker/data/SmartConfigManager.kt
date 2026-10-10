package com.example.autoclicker.data

import android.content.Context

/**
 * Итоговый набор правил, с которым работает SmartEngine.
 */
data class EffectiveConfig(
    val mode: SmartConfigMode,
    val rules: List<SmartRule>,
    val scanIntervalMs: Long,
    val idleTimeoutMin: Int,
    val builtinCount: Int,
    val customCount: Int
)

/**
 * Менеджер конфигов. После шага 2 пользовательские конфиги убраны:
 * всегда используется только встроенный конфиг (PUBG).
 * API оставлен совместимым, чтобы движок и сервисы продолжали работать.
 */
class SmartConfigManager private constructor() {

    /** Вызывается при смене настроек конфига (в режиме «только встроенный» не используется). */
    var onConfigChangedListener: (() -> Unit)? = null

    fun getMode(): SmartConfigMode = SmartConfigMode.DEFAULT_ONLY

    @Suppress("UNUSED_PARAMETER")
    fun setMode(mode: SmartConfigMode) {
        // Пользовательские конфиги отключены: режим всегда DEFAULT_ONLY.
    }

    fun getActiveName(): String = ""

    @Suppress("UNUSED_PARAMETER")
    fun setActiveName(name: String) {
        // Пользовательские конфиги отключены.
    }

    fun listConfigs(): List<String> = emptyList()

    @Suppress("UNUSED_PARAMETER")
    fun delete(name: String): Boolean = false

    fun describeActive(): String = "по умолчанию (PUBG)"

    @Suppress("UNUSED_PARAMETER")
    fun buildEffectiveRules(silent: Boolean = false): EffectiveConfig {
        val cfg = SmartConfig.createDefaultConfig()
        val rules = cfg.rules.sortedBy { it.priority }
        return EffectiveConfig(
            mode = SmartConfigMode.DEFAULT_ONLY,
            rules = rules,
            scanIntervalMs = cfg.scanIntervalMs,
            idleTimeoutMin = cfg.idleTimeoutMin,
            builtinCount = rules.count { it.builtin },
            customCount = rules.count { !it.builtin }
        )
    }

    companion object {
        @Volatile
        private var instance: SmartConfigManager? = null

        @Suppress("UNUSED_PARAMETER")
        fun getInstance(context: Context): SmartConfigManager =
            instance ?: synchronized(this) {
                instance ?: SmartConfigManager().also { instance = it }
            }
    }
}
