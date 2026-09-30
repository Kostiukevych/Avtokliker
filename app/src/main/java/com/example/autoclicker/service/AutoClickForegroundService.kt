package com.example.autoclicker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.autoclicker.MainActivity
import com.example.autoclicker.R
import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.engine.CycleController
import com.example.autoclicker.engine.GestureExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground Service для устойчивой работы кликера в фоновом режиме
 * при нахождении пользователя в сторонних приложениях и полноэкранных играх.
 */
class AutoClickForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var cycleController: CycleController
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        val settingsRepo = SettingsRepository.getInstance(applicationContext)
        val gestureExecutor = GestureExecutor()
        cycleController = CycleController.getInstance(gestureExecutor, settingsRepo)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannel()
        startForegroundWithNotification("Готов к работе")
        observeStatus()
        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "ForegroundService создан")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                cycleController.start()
            }
            ACTION_STOP -> {
                cycleController.stop()
            }
            ACTION_SHUTDOWN -> {
                cycleController.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun observeStatus() {
        serviceScope.launch {
            combine(
                cycleController.status,
                cycleController.cycleNumber,
                cycleController.currentAction,
                cycleController.countdownText
            ) { status, cycleNum, currentAct, countdown ->
                val title = if (cycleNum > 0) "AutoClicker: ${status.name} (Цикл #$cycleNum)" else "AutoClicker: ${status.name}"
                val text = if (countdown.isNotEmpty()) "$currentAct [$countdown]" else currentAct
                Pair(title, text)
            }.collect { (title, text) ->
                updateNotification(text, title)
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun startForegroundWithNotification(contentText: String) {
        val notification = buildNotification(contentText, "IDLE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(contentText: String, status: String) {
        val notification = buildNotification(contentText, status)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(contentText: String, status: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val startIntent = Intent(this, AutoClickForegroundService::class.java).apply {
            action = ACTION_START
        }
        val startPendingIntent = PendingIntent.getService(
            this,
            1,
            startIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AutoClickForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AutoClicker: $status")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_play)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_play, getString(R.string.action_start), startPendingIntent)
            .addAction(R.drawable.ic_stop, getString(R.string.action_stop), stopPendingIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        serviceScope.cancel()
        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "ForegroundService остановлен")
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "autoclicker_foreground_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.autoclicker.ACTION_START"
        const val ACTION_STOP = "com.example.autoclicker.ACTION_STOP"
        const val ACTION_SHUTDOWN = "com.example.autoclicker.ACTION_SHUTDOWN"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            if (isRunning) return
            try {
                val intent = Intent(context, AutoClickForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Throwable) {
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "ERROR: Не удалось запустить ForegroundService: ${e.message}",
                    isError = true
                )
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, AutoClickForegroundService::class.java).apply {
                    action = ACTION_SHUTDOWN
                }
                context.startService(intent)
            } catch (e: Throwable) {
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "ERROR: Ошибка остановки ForegroundService: ${e.message}",
                    isError = true
                )
            }
        }
    }
}
