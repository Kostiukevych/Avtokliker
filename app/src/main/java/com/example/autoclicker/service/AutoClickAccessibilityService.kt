package com.example.autoclicker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.example.autoclicker.data.EventLogManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Сервис специальных возможностей (AccessibilityService).
 *
 * Отвечает ТОЛЬКО за выполнение жестов (dispatchGesture) через GestureExecutor
 * и получение снимков экрана (takeScreenshot) для умного режима.
 * НЕ запускает бесконечных циклов автоматизации самостоятельно.
 */
class AutoClickAccessibilityService : AccessibilityService() {

    private var lastScreenshotTime: Long = 0L
    private var lastScreenshotErrorLogTime: Long = 0L

    /**
     * Создает снимок экрана в памяти через AccessibilityService.takeScreenshot API 30+.
     * Не чаще одного снимка в секунду.
     */
    suspend fun takeScreenshotBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }

        val now = System.currentTimeMillis()
        val elapsed = now - lastScreenshotTime
        if (elapsed < 1000L) {
            delay(1000L - elapsed)
        }
        lastScreenshotTime = System.currentTimeMillis()

        return suspendCancellableCoroutine { continuation ->
            val executor = ContextCompat.getMainExecutor(this)
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        try {
                            val hardwareBuffer = screenshotResult.hardwareBuffer
                            val colorSpace = screenshotResult.colorSpace
                            val hwBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                            val argbBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            hwBitmap?.recycle()
                            hardwareBuffer.close()

                            if (continuation.isActive) {
                                continuation.resume(argbBitmap)
                            } else {
                                argbBitmap?.recycle()
                            }
                        } catch (e: Throwable) {
                            val errNow = System.currentTimeMillis()
                            if (errNow - lastScreenshotErrorLogTime >= 10000L) {
                                lastScreenshotErrorLogTime = errNow
                                EventLogManager.log(
                                    EventLogManager.TAG_AUTO_CLICKER,
                                    "SMART: снимок не удался: ${e.message ?: e.javaClass.simpleName}",
                                    isError = true
                                )
                            }
                            if (continuation.isActive) {
                                continuation.resume(null)
                            }
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        val errNow = System.currentTimeMillis()
                        if (errNow - lastScreenshotErrorLogTime >= 10000L) {
                            lastScreenshotErrorLogTime = errNow
                            EventLogManager.log(
                                EventLogManager.TAG_AUTO_CLICKER,
                                "SMART: снимок не удался, код $errorCode",
                                isError = true
                            )
                        }
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                }
            )
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val caps = serviceInfo?.capabilities ?: 0
        val canPerform = (caps and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0
        EventLogManager.log(
            EventLogManager.TAG_ACCESSIBILITY,
            "AccessibilityService подключен (canPerformGestures=$canPerform)"
        )
        AccessibilityServiceHolder.setService(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // События интерфейса не требуются для кликера по координатам
    }

    override fun onInterrupt() {
        EventLogManager.log(
            EventLogManager.TAG_ACCESSIBILITY,
            "AccessibilityService прерван системой"
        )
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AccessibilityServiceHolder.setService(null)
        EventLogManager.log(
            EventLogManager.TAG_ACCESSIBILITY,
            "AccessibilityService отключен"
        )
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AccessibilityServiceHolder.setService(null)
        super.onDestroy()
    }
}

/**
 * Потокобезопасный держатель активного экземпляра AccessibilityService.
 */
object AccessibilityServiceHolder {
    private val _service = MutableStateFlow<AutoClickAccessibilityService?>(null)
    val service: StateFlow<AutoClickAccessibilityService?> = _service.asStateFlow()

    val isConnected: Boolean
        get() = _service.value != null

    fun setService(instance: AutoClickAccessibilityService?) {
        _service.value = instance
    }
}
