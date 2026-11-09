package com.antolabs.mailmysms

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.security.MessageDigest
import java.util.Date
import kotlin.concurrent.thread

class CallLogBackupService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        running = true
        cancelled = false
        notifications = BackupNotifications(this, NOTIFICATION_ID, "call_log_backup", "Call log backup", "calls")
        notifications!!.startForeground()
        thread { runBackup() }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun runBackup() {
        val context = applicationContext
        val tracker = BackupTracker.get(context)
        val gmailUser = SettingsStore.emailUser(context)
        val appPassword = SettingsStore.appPassword(context)
        val imapHost = SettingsStore.imapHost(context)
        val imapPort = SettingsStore.imapPort(context)
        val folderName = SettingsStore.callLogBackupLabel(context)
        val template = SettingsStore.callTemplate(context)
        val notifier = notifications!!

        var appender: ResilientAppender? = null
        try {
            appender = ResilientAppender(TAG, gmailUser, appPassword, imapHost, imapPort, folderName)
            val existingMessageIds = appender.fetchExistingMessageIds()

            val calls = CallLogReader.queryAll(context)
            val total = calls.size
            var done = 0

            for (call in calls) {
                if (cancelled) break

                val typeLabel = CallLogReader.typeLabel(call.type)
                val messageId = computeMessageId(call.number, call.dateMillis, call.type, call.durationSeconds)
                val alreadyDone = tracker.isImported(BackupTracker.TYPE_CALL, call.id, gmailUser) ||
                    existingMessageIds.contains(messageId)

                if (!alreadyDone) {
                    val tokens = EventTokens.forCall(context, call.number, typeLabel, call.durationSeconds, call.dateMillis, call.phoneAccountId)
                    val from = TemplateEngine.render(template.from, tokens)
                    val fromName = TemplateEngine.render(template.fromName, tokens)
                    val to = TemplateEngine.render(template.to, tokens).ifBlank { gmailUser }
                    val cc = TemplateEngine.render(template.cc, tokens)
                    val bcc = TemplateEngine.render(template.bcc, tokens)
                    val subject = TemplateEngine.render(template.subject, tokens)
                    val renderedBody = TemplateEngine.render(template.body, tokens)

                    val sent = appender.append(
                        from, fromName, to, cc, bcc, subject, renderedBody,
                        messageId, Date(call.dateMillis), null, null, MAX_ATTEMPTS_PER_MESSAGE
                    ) { cancelled }

                    if (sent) {
                        tracker.markImported(BackupTracker.TYPE_CALL, call.id, gmailUser, messageId)
                    } else {
                        Log.e(TAG, "Giving up on call ${call.id} - will retry on next run")
                    }
                    Thread.sleep(PACE_DELAY_MS)
                }

                done++
                if (done % 20 == 0) notifier.update(done, total)
            }

            notifier.update(done, total, finished = true)
        } catch (e: Exception) {
            Log.e(TAG, "Backup run failed", e)
            val count = tracker.importedCount(BackupTracker.TYPE_CALL, gmailUser)
            notifier.update(count, count, finished = true)
        } finally {
            appender?.close()
            running = false
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun computeMessageId(number: String, date: Long, type: Int, duration: Long): String {
        val raw = "$number|$date|$type|$duration"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "<call-$hex@mailmysms.local>"
    }

    companion object {
        private const val TAG = "CallLogBackupService"
        private const val NOTIFICATION_ID = 44
        private const val PACE_DELAY_MS = 350L
        private const val MAX_ATTEMPTS_PER_MESSAGE = 3

        @Volatile private var running = false
        @Volatile private var cancelled = false
        @Volatile private var notifications: BackupNotifications? = null

        fun isRunning() = running

        fun start(context: Context) {
            if (running) return
            context.startForegroundService(Intent(context, CallLogBackupService::class.java))
        }

        fun cancel() {
            cancelled = true
        }
    }
}
