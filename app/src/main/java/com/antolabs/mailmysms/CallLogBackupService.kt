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
        var done = 0
        var total = 0
        try {
            notifier.updatePhase("Connecting to your mailbox…")
            appender = ResilientAppender(TAG, gmailUser, appPassword, imapHost, imapPort, folderName)
            currentAppender = appender

            notifier.updatePhase("Checking what's already backed up…")
            val existingMessageIds = appender.fetchExistingMessageIds()
            if (cancelled) return

            notifier.updatePhase("Reading call log history…")
            val calls = CallLogReader.queryAll(context)
            total = calls.size

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
        } catch (e: Exception) {
            Log.e(TAG, "Backup run failed", e)
            val count = tracker.importedCount(BackupTracker.TYPE_CALL, gmailUser)
            done = count
            total = maxOf(total, count)
        } finally {
            appender?.close()
            currentAppender = null
            notifier.finish(done, total, cancelled)
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
        @Volatile private var currentAppender: ResilientAppender? = null

        fun isRunning() = running

        /** (done, total) for the run in progress, or (0, 0) if nothing is running. */
        fun progress(): Pair<Int, Int> = notifications?.let { it.lastDone to it.lastTotal } ?: (0 to 0)
        fun phase(): String? = notifications?.lastPhase

        fun start(context: Context) {
            if (running) return
            context.startForegroundService(Intent(context, CallLogBackupService::class.java))
        }

        /** Setting the flag alone isn't enough: the run may be blocked inside a single synchronous
         *  IMAP call (fetching existing Message-IDs, or an append) with no opportunity to check it.
         *  Forcibly closing the in-flight connection unblocks that call via an IOException, same as
         *  any other dropped connection the retry logic already handles - closing is itself a
         *  network round-trip (IMAP LOGOUT), so it runs off the calling (likely UI) thread. */
        fun cancel() {
            cancelled = true
            val appender = currentAppender
            if (appender != null) thread { appender.close() }
        }
    }
}
