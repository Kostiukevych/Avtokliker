package com.example.autoclicker

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.engine.RunCoordinator
import com.example.autoclicker.service.AutoClickAccessibilityService
import com.example.autoclicker.service.AutoClickForegroundService
import com.example.autoclicker.service.FloatingOverlayService
import com.example.autoclicker.ui.MainScreen
import com.example.autoclicker.ui.theme.AutoClickerTheme

/**
 * Главный экран приложения.
 *
 * Отвечает за:
 * 1. Проверку и запрос разрешения SYSTEM_ALERT_WINDOW (наложение поверх окон).
 * 2. Проверку и направление пользователя в настройки AccessibilityService.
 * 3. Запуск / остановку FloatingOverlayService (плавающее окно управления).
 * 4. Запуск / остановку AutoClickForegroundService (фоновая служба).
 * 5. Отображение интерфейса настроек (Compose).
 */
class MainActivity : ComponentActivity() {

    private lateinit var settingsRepository: SettingsRepository

    // Состояния разрешений для реактивного обновления в Compose
    private val hasOverlayPermissionState = mutableStateOf(false)
    private val hasAccessibilityServiceState = mutableStateOf(false)

    // ContentObserver для отслеживания изменения состояния AccessibilityService в реальном времени
    private val accessibilityObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            checkPermissions()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        settingsRepository = SettingsRepository(this)

        setContent {
            AutoClickerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val hasOverlay by remember { hasOverlayPermissionState }
                    val hasAccessibility by remember { hasAccessibilityServiceState }

                    // Синхронизируем состояние запуска при каждом возвращении
                    val isRunning by RunCoordinator.isRunning.collectAsState()

                    MainScreen(
                        settingsRepository = settingsRepository,
                        hasOverlayPermission = hasOverlay,
                        hasAccessibilityPermission = hasAccessibility,
                        isAutomationRunning = isRunning,
                        onRequestOverlayPermission = { requestOverlayPermission() },
                        onOpenAccessibilitySettings = { openAccessibilitySettings() },
                        onStartAutomation = { startAutomation() },
                        onStopAutomation = { stopAutomation() },
                        onToggleOverlay = { enabled ->
                            if (enabled) {
                                startOverlayService()
                            } else {
                                stopOverlayService()
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
        registerAccessibilityObserver()

        // Проверяем, запущен ли оверлей, и если сервис упал - синхронизируем UI при необходимости
        val isOverlayActive = isServiceRunning(FloatingOverlayService::class.java)
        // Обновляем состояние оверлея при возврате в приложение
    }

    override fun onPause() {
        super.onPause()
        unregisterAccessibilityObserver()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ПРОВЕРКА И ЗАПРОС РАЗРЕШЕНИЙ
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Проверяет текущее состояние обоих ключевых разрешений.
     */
    private fun checkPermissions() {
        hasOverlayPermissionState.value = Settings.canDrawOverlays(this)
        // Включён в системе ИЛИ уже привязан к процессу — кнопка Accessibility должна пропасть
        hasAccessibilityServiceState.value =
            com.example.autoclicker.service.AccessibilityStatus.isEnabledInSystem(this) ||
            AutoClickAccessibilityService.isServiceRunning
    }

    /**
     * Открывает экран настроек для выдачи разрешения "Поверх других приложений".
     */
    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            try {
                startActivity(intent)
            } catch (e: Exception) {
                // На некоторых кастомных прошивках может упасть прямой Intent по package
                val fallbackIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                startActivity(fallbackIntent)
            }
        } else {
            Toast.makeText(this, "Разрешение наложения уже получено", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Открывает системный экран настроек Специальных возможностей.
     */
    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        try {
            startActivity(intent)
            Toast.makeText(
                this,
                "Найдите 'Auto Clicker' и включите службу",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось открыть настройки", Toast.LENGTH_SHORT).show()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // УПРАВЛЕНИЕ СЛУЖБАМИ
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Запускает плавающее окно (FloatingOverlayService).
     */
    private fun startOverlayService() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Сначала разрешите отображение поверх других приложений",
                Toast.LENGTH_SHORT
            ).show()
            requestOverlayPermission()
            return
        }

        val intent = Intent(this, FloatingOverlayService::class.java).apply {
            action = FloatingOverlayService.ACTION_SHOW_OVERLAY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    /**
     * Останавливает плавающее окно.
     */
    private fun stopOverlayService() {
        val intent = Intent(this, FloatingOverlayService::class.java).apply {
            action = FloatingOverlayService.ACTION_HIDE_OVERLAY
        }
        startService(intent)
    }

    /**
     * Запускает цикл автоматизации.
     */
    private fun startAutomation() {
        if (!AutoClickAccessibilityService.isServiceRunning) {
            Toast.makeText(
                this,
                "Служба специальных возможностей не активна!",
                Toast.LENGTH_SHORT
            ).show()
            openAccessibilitySettings()
            return
        }

        // Запускаем ForegroundService для защиты от выгрузки системой
        val serviceIntent = Intent(this, AutoClickForegroundService::class.java).apply {
            action = AutoClickForegroundService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Стартуем оверлей если он еще не открыт
        if (Settings.canDrawOverlays(this)) {
            startOverlayService()
        }
    }

    /**
     * Останавливает цикл автоматизации.
     */
    private fun stopAutomation() {
        val serviceIntent = Intent(this, AutoClickForegroundService::class.java).apply {
            action = AutoClickForegroundService.ACTION_STOP
        }
        startService(serviceIntent)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // ─────────────────────────────────────────────────────────────────────────

    private fun registerAccessibilityObserver() {
        try {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                accessibilityObserver
            )
        } catch (_: Exception) {}
    }

    private fun unregisterAccessibilityObserver() {
        try {
            contentResolver.unregisterContentObserver(accessibilityObserver)
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == serviceClass.name }
    }
}
