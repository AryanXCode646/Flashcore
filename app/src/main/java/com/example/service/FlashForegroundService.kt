package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity

/**
 * Foreground Service for background USB I/O operations.
 *
 * Runs with `FOREGROUND_SERVICE_TYPE_DATA_SYNC` on Android 14+ and holds a
 * `PARTIAL_WAKE_LOCK` to ensure CPU does not sleep during long GB-scale writes.
 */
class FlashForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "flashcore_io_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_CANCEL_FLASH = "com.example.action.CANCEL_FLASH"

        fun startService(context: Context) {
            val intent = Intent(context, FlashForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, FlashForegroundService::class.java)
            context.stopService(intent)
        }
    }

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationManager: NotificationManager? = null

    inner class LocalBinder : Binder() {
        fun getService(): FlashForegroundService = this@FlashForegroundService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        // Acquire partial wake lock
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FlashCore:IoWakeLock").apply {
            setReferenceCounted(false)
            acquire(2 * 60 * 60 * 1000L) // 2 hour max safeguard
        }

        val initialNotification = buildNotification("Initializing USB Flasher...", 0, 0.0, 0L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "USB Flashing Operations",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows real-time progress, throughput speed, and ETA during USB writing."
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    fun updateProgress(statusText: String, progressPercent: Int, speedMBps: Double, etaSeconds: Long) {
        val notification = buildNotification(statusText, progressPercent, speedMBps, etaSeconds)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(statusText: String, progressPercent: Int, speedMBps: Double, etaSeconds: Long): Notification {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val subText = if (speedMBps > 0.0) {
            "%.1f MB/s • ETA: %02d:%02d".format(speedMBps, etaSeconds / 60, etaSeconds % 60)
        } else {
            "Preparing sectors..."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FlashCore: $statusText")
            .setContentText(subText)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setProgress(100, progressPercent, progressPercent == 0)
            .setContentIntent(pIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
