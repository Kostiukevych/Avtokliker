package com.example.autoclicker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.example.autoclicker.data.EventLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

class AutoClickAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AccessibilityServiceHolder.setService(this)
        AccessibilityStatus.setRunning(true)
        EventLogManager.log(EventLogManager.TAG_ACCESSIBILITY, "ACCESSIBILITY: Сервис подключен")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Не требуется для кликера
    }

    override fun onInterrupt() {
        EventLogManager.log(EventLogManager.TAG_ACCESSIBILITY, "ACCESSIBILITY: Сервис прерван")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        AccessibilityServiceHolder.setService(null)
        AccessibilityStatus.setRunning(false)
        EventLogManager.log(EventLogManager.TAG_ACCESSIBILITY, "ACCESSIBILITY: Сервис отключен")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        AccessibilityServiceHolder.setService(null)
        AccessibilityStatus.setRunning(false)
        serviceScope.cancel()
        screenshotExecutor.shutdown()
    }

    suspend fun takeScreenshotCompat(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "SMART: Скриншот не поддерживается на Android < 11")
            return null
        }

        return suspendCancellableCoroutine { continuation ->
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    screenshotExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            try {
                                val hardwareBuffer = screenshotResult.hardwareBuffer
                                val colorSpace = screenshotResult.colorSpace
                                val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                                val copy = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                hardwareBuffer.close()
                                if (continuation.isActive) {
                                    continuation.resume(copy)
                                }
                            } catch (e: Exception) {
                                EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "SMART: Ошибка обработки скриншота: ${e.message}")
                                if (continuation.isActive) {
                                    continuation.resume(null)
                                }
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "SMART: Ошибка захвата экрана: код $errorCode")
                            if (continuation.isActive) {
                                continuation.resume(null)
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "SMART: Исключение при вызове takeScreenshot: ${e.message}")
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            }
        }
    }

    suspend fun takeScreenshotBitmap(minIntervalMs: Long = 0L): Bitmap? = takeScreenshotCompat()

    companion object {
        @Volatile
        var instance: AutoClickAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null

        val isServiceRunning: Boolean
            get() = instance != null
    }
}
