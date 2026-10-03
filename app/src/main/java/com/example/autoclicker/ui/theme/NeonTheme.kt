package com.example.autoclicker.ui.theme

import android.graphics.Color as AndroidColor

/**
 * Глобальный синглтон параметров неона, доступный сервисам (включая FloatingOverlayService)
 * без жесткой привязки к Jetpack Compose.
 */
object NeonTheme {
    /**
     * Яркость неона в диапазоне 0.0f .. 1.0f (по умолчанию 0.85f).
     */
    @Volatile
    var brightness: Float = 0.85f

    /**
     * Текущий оттенок (Hue 0..360), обновляемый из анимации Compose или системного таймера.
     */
    @Volatile
    var currentHue: Float = 0f

    /**
     * Возвращает текущий Hue (0..360) с плавным циклом за 36 секунд.
     */
    fun getHue(): Float {
        val h = currentHue
        return if (h > 0f) h else ((System.currentTimeMillis() % 36000L) / 36000f) * 360f
    }

    /**
     * Возвращает чистый цвет неона в формате Android ARGB Int.
     */
    fun getCurrentColorInt(): Int {
        val hsv = floatArrayOf(getHue(), 1.0f, 1.0f)
        return AndroidColor.HSVToColor(hsv)
    }

    /**
     * Возвращает цвет свечения неона с учетом яркости и прозрачности в формате ARGB Int.
     */
    fun getGlowColorInt(alpha: Float = 1.0f): Int {
        val base = getCurrentColorInt()
        val bri = brightness.coerceIn(0.1f, 1.5f)
        val finalAlpha = (alpha.coerceIn(0f, 1f) * bri * 255).toInt().coerceIn(0, 255)
        return AndroidColor.argb(
            finalAlpha,
            AndroidColor.red(base),
            AndroidColor.green(base),
            AndroidColor.blue(base)
        )
    }

    /**
     * Возвращает цвет рамки, смешанный с белым цветом (50% белого) как в CSS color-mix(in srgb, var(--neon) 50%, #fff).
     */
    fun getBorderColorInt(): Int {
        val base = getCurrentColorInt()
        val r = ((AndroidColor.red(base) + 255) / 2).coerceIn(0, 255)
        val g = ((AndroidColor.green(base) + 255) / 2).coerceIn(0, 255)
        val b = ((AndroidColor.blue(base) + 255) / 2).coerceIn(0, 255)
        return AndroidColor.rgb(r, g, b)
    }
}
