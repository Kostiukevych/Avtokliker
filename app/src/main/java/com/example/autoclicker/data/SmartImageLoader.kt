package com.example.autoclicker.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

object SmartImageLoader {
    val ALLOWED_EXTENSIONS: Set<String> = setOf(
        "json", "png", "jpg", "jpeg", "webp", "bmp", "gif", "heic", "heif"
    )

    private val IMAGE_EXTENSIONS: Set<String> = ALLOWED_EXTENSIONS - "json"

    fun isImageExt(name: String): Boolean {
        val ext = name.substringAfterLast('.', name).lowercase(Locale.ROOT)
        return IMAGE_EXTENSIONS.contains(ext)
    }

    data class DecodedImage(val bitmap: Bitmap, val sampleSize: Int)

    fun resolve(dir: File, name: String): File? {
        if (!dir.exists() || !dir.isDirectory) return null
        val direct = File(dir, name)
        if (direct.exists()) return direct
        val files = dir.listFiles() ?: return null
        return files.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    fun decode(file: File): Result<DecodedImage> {
        val ext = file.extension.lowercase(Locale.ROOT)
        if ((ext == "heic" || ext == "heif") && Build.VERSION.SDK_INT < 28) {
            return Result.failure(
                IllegalStateException("Формат HEIC не поддерживается на этой версии Android, используйте PNG или JPG")
            )
        }

        return try {
            if (!file.exists() || !file.isFile) {
                return Result.failure(IllegalStateException("Не удалось прочитать картинку ${file.name}"))
            }

            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, boundsOptions)
            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
                return Result.failure(IllegalStateException("Не удалось прочитать картинку ${file.name}"))
            }

            val maxDim = maxOf(boundsOptions.outWidth, boundsOptions.outHeight)
            var sampleSize = 1
            while (maxDim / sampleSize > 4096) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = sampleSize
            }

            val bmp = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
                ?: return Result.failure(IllegalStateException("Не удалось прочитать картинку ${file.name}"))

            Result.success(DecodedImage(bmp, sampleSize))
        } catch (t: Throwable) {
            Result.failure(IllegalStateException("Не удалось прочитать картинку ${file.name}", t))
        }
    }

    fun cropByBox(src: Bitmap, box: FloatArray): Bitmap? {
        if (box.size < 4 || src.isRecycled) return null
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return null

        val minX = minOf(box[0], box[2]).coerceIn(0f, 1f)
        val maxX = maxOf(box[0], box[2]).coerceIn(0f, 1f)
        val minY = minOf(box[1], box[3]).coerceIn(0f, 1f)
        val maxY = maxOf(box[1], box[3]).coerceIn(0f, 1f)

        val l = (minX * w).roundToInt().coerceIn(0, w - 1)
        val t = (minY * h).roundToInt().coerceIn(0, h - 1)
        val r = (maxX * w).roundToInt().coerceIn(l + 1, w)
        val b = (maxY * h).roundToInt().coerceIn(t + 1, h)

        val cropW = maxOf(1, r - l)
        val cropH = maxOf(1, b - t)

        return try {
            Bitmap.createBitmap(src, l, t, cropW, cropH)
        } catch (t: Throwable) {
            null
        }
    }

    fun isFlat(bmp: Bitmap): Boolean {
        if (bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) return true
        val sampleW = bmp.width.coerceAtMost(64)
        val sampleH = bmp.height.coerceAtMost(64)
        val scaled = if (sampleW != bmp.width || sampleH != bmp.height) {
            try {
                Bitmap.createScaledBitmap(bmp, sampleW, sampleH, true)
            } catch (t: Throwable) {
                bmp
            }
        } else {
            bmp
        }

        val count = scaled.width * scaled.height
        if (count <= 1) return true

        val pixels = IntArray(count)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        if (scaled !== bmp) {
            scaled.recycle()
        }

        var sum = 0.0
        var sumSq = 0.0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val y = 0.299 * r + 0.587 * g + 0.114 * b
            sum += y
            sumSq += y * y
        }
        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)
        val stdDev = if (variance > 0.0) sqrt(variance) else 0.0
        return stdDev < 6.0
    }
}
