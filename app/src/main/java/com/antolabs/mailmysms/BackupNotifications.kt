package com.antolabs.mailmysms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat

/** Shared foreground-notification plumbing for the bulk backup services. */
class BackupNotifications(
    private val service: Service,
    private val notificationId: Int,
    private val channelId: String,
    private val channelName: String,
    private val noun: String
) {
    fun startForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = service.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW))
        }
        val notification = build(0, 0, finished = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            service.startForeground(notificationId, notification)
        }
    }

    @Volatile var lastDone: Int = 0
        private set
    @Volatile var lastTotal: Int = 0
        private set

    fun update(done: Int, total: Int, finished: Boolean = false) {
        lastDone = done
        lastTotal = total
        val manager = service.getSystemService(NotificationManager::class.java)
        manager?.notify(notificationId, build(done, total, finished))
    }

    private fun build(done: Int, total: Int, finished: Boolean): Notification {
        val pct = if (total > 0) (done * 100 / total) else 0
        val text = if (finished) {
            "Backed up $done of $total $noun"
        } else {
            "Backing up $noun: $done / $total ($pct%)"
        }
        return NotificationCompat.Builder(service, channelId)
            .setContentTitle("MailMySMS")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(!finished)
            .setProgress(if (total > 0) total else 100, done, total == 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
