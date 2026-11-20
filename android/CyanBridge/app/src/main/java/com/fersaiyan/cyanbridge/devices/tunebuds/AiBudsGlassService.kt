package com.fersaiyan.cyanbridge.devices.tunebuds

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.ui.AiBudsMainActivity

/**
 * Фоновый Foreground Service (connectedDevice | microphone), аналогичный AIService из aibuds.apk.
 * Не даёт Android усыпить Bluetooth SPP и разрешает работу микрофона на заблокированном экране.
 */
class AiBudsGlassService : Service() {
    companion object {
        private const val CHANNEL_ID = "aibuds_glasses_connection"
        private const val NOTIFICATION_ID = 4102
        private const val EXTRA_DEVICE_NAME = "device_name"

        fun start(context: Context, deviceName: String?) {
            val appCtx = context.applicationContext
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(appCtx, Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val intent = Intent(appCtx, AiBudsGlassService::class.java).apply {
                putExtra(EXTRA_DEVICE_NAME, deviceName ?: "AI Glasses")
            }
            runCatching {
                ContextCompat.startForegroundService(appCtx, intent)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.applicationContext.stopService(Intent(context.applicationContext, AiBudsGlassService::class.java))
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val deviceName = intent?.getStringExtra(EXTRA_DEVICE_NAME) ?: "AI Glasses"
        val notification = buildNotification(deviceName)

        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val hasMic = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                val types = if (hasMic) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                }
                startForeground(NOTIFICATION_ID, notification, types)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        }.getOrDefault(false)

        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Подключение очков AiBuds",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Поддерживает связь с очками и голосовой вызов Gemini при выключенном экране"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(deviceName: String): Notification {
        val openIntent = Intent(this, AiBudsMainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pending = PendingIntent.getActivity(this, 0, openIntent, piFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_aibuds_launcher)
            .setContentTitle("$deviceName подключены")
            .setContentText("Голосовой ассистент Gemini активен в фоне")
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }
}
