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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fersaiyan.cyanbridge.R
import com.fersaiyan.cyanbridge.ai.IncomingCallAnnouncer
import com.fersaiyan.cyanbridge.ui.AiBudsMainActivity

/**
 * Держит SPP-соединение с очками, озвучку входящих вызовов и доступ к Bluetooth-микрофону
 * активными, когда приложение свёрнуто или экран телефона заблокирован.
 */
class AiBudsForegroundService : Service() {

    companion object {
        private const val TAG = "AiBudsFGS"
        private const val CHANNEL_ID = "aibuds_glasses_connection"
        private const val NOTIFICATION_ID = 4102
        private const val ACTION_START = "com.fersaiyan.cyanbridge.action.START_GLASSES_FGS"
        private const val ACTION_STOP = "com.fersaiyan.cyanbridge.action.STOP_GLASSES_FGS"
        private const val EXTRA_DEVICE_NAME = "extra_device_name"

        fun start(context: Context, deviceName: String?) {
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, AiBudsForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DEVICE_NAME, deviceName ?: "AI Glasses")
            }
            runCatching {
                ContextCompat.startForegroundService(appCtx, intent)
            }.onFailure { err ->
                Log.w(TAG, "Не удалось запустить фоновый сервис: ${err.message}")
            }
        }

        fun stop(context: Context) {
            val appCtx = context.applicationContext
            runCatching {
                appCtx.stopService(Intent(appCtx, AiBudsForegroundService::class.java))
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            IncomingCallAnnouncer.getInstance(applicationContext).stopListening()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        val deviceName = intent?.getStringExtra(EXTRA_DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: "AI Glasses"
        startForegroundSafe(deviceName)
        IncomingCallAnnouncer.getInstance(applicationContext).startListening()
        return START_STICKY
    }

    override fun onDestroy() {
        IncomingCallAnnouncer.getInstance(applicationContext).stopListening()
        stopForegroundCompat()
        super.onDestroy()
    }

    private fun startForegroundSafe(deviceName: String) {
        val notification = buildNotification(deviceName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasMic = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasMic) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            try {
                startForeground(NOTIFICATION_ID, notification, types)
            } catch (sec: SecurityException) {
                Log.w(TAG, "Нет прав на FGS microphone, запуск только connectedDevice: ${sec.message}")
                runCatching {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                    )
                }.onFailure { err ->
                    Log.e(TAG, "Ошибка запуска FGS: ${err.message}")
                    stopSelf()
                }
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Подключение умных очков",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Поддерживает связь с очками, озвучку звонков и голосовой ИИ при выключенном экране"
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
        val pendingIntent = PendingIntent.getActivity(this, 0, openIntent, piFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_aibuds_launcher)
            .setContentTitle("AiBuds Studio")
            .setContentText("Очки $deviceName подключены • ИИ и звонки активны")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
