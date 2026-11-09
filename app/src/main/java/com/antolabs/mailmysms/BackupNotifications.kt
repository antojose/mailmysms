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
    /** A human status for phases with no (done/total) to report yet - e.g. "Connecting...",
     *  "Reading message history...". Cleared once real progress starts. */
    @Volatile var lastPhase: String? = null
        private set

    fun update(done: Int, total: Int, finished: Boolean = false) {
        lastDone = done
        lastTotal = total
        lastPhase = null
        val manager = service.getSystemService(NotificationManager::class.java)
        manager?.notify(notificationId, build(done, total, finished))
    }

    /** For setup/reading phases before there's a (done/total) to show - keeps the notification
     *  (and the in-app status text, via lastPhase) from sitting silently at "0/0" while real work
     *  is happening underneath. */
    fun updatePhase(text: String) {
        lastPhase = text
        val manager = service.getSystemService(NotificationManager::class.java)
        manager?.notify(notificationId, buildPhase(text))
    }

    /** Always call exactly once as a run ends, however it ends (completed, cancelled, or errored
     *  out) - leaves a clean, dismissable notification behind instead of whatever ongoing phase/
     *  progress state happened to be showing at the moment the run stopped. Without this, a run
     *  cancelled mid-phase (e.g. "Checking what's already backed up…") left that ongoing - and
     *  therefore non-swipeable - notification stuck forever, looking identical to still running. */
    fun finish(done: Int, total: Int, cancelled: Boolean) {
        lastDone = done
        lastTotal = total
        lastPhase = null
        val text = if (cancelled) {
            if (done > 0) "Backup cancelled - ${done.grouped()} $noun sent before stopping" else "Backup cancelled"
        } else {
            "Backed up ${done.grouped()} of ${total.grouped()} $noun"
        }
        val notification = NotificationCompat.Builder(service, channelId)
            .setContentTitle("MailMySMS")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val manager = service.getSystemService(NotificationManager::class.java)
        manager?.notify(notificationId, notification)
    }

    private fun build(done: Int, total: Int, finished: Boolean): Notification {
        val pct = if (total > 0) (done * 100 / total) else 0
        val text = if (finished) {
            "Backed up ${done.grouped()} of ${total.grouped()} $noun"
        } else {
            "Backing up $noun: ${done.grouped()} / ${total.grouped()} ($pct%)"
        }
        return NotificationCompat.Builder(service, channelId)
            .setContentTitle("MailMySMS")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setOngoing(!finished)
            .setProgress(if (total > 0) total else 100, done, total == 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun buildPhase(text: String): Notification {
        return NotificationCompat.Builder(service, channelId)
            .setContentTitle("MailMySMS")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setOngoing(true)
            .setProgress(100, 0, true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
