package com.antolabs.mailmysms

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.security.MessageDigest
import java.util.Date
import kotlin.concurrent.thread

// threadKey is namespaced by source, not just the raw numeric thread_id: SMS and MMS share one
// thread-numbering space (Android's MmsSmsProvider unifies them by design), but RCS's thread_id
// comes from a completely separate provider (ImProvider) with its own independent numbering - an
// RCS thread_id could numerically collide with an unrelated SMS/MMS thread_id for a different
// conversation. Without the namespace prefix, that collision would wrongly chain two unrelated
// conversations together via In-Reply-To/References.
private sealed class HistoryItem(val dateMillis: Long, val threadKey: String) {
    class Sms(val id: Long, val address: String, val body: String, dateMillis: Long, threadId: Long, val subId: Int?, val isOutgoing: Boolean) :
        HistoryItem(dateMillis, "smsmms:$threadId")
    class Mms(val entry: MmsEntry) : HistoryItem(entry.dateMillis, "smsmms:${entry.threadId}")
    class Rcs(val entry: RcsEntry) : HistoryItem(entry.dateMillis, "rcs:${entry.threadId}")
}

class SmsBackupService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        running = true
        cancelled = false
        notifications = BackupNotifications(this, NOTIFICATION_ID, "sms_backup", "SMS backup", "messages")
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
        val folderName = SettingsStore.smsBackupLabel(context)
        val template = SettingsStore.smsTemplate(context)
        val notifier = notifications!!

        var appender: ResilientAppender? = null
        try {
            notifier.updatePhase("Connecting to your mailbox…")
            appender = ResilientAppender(TAG, gmailUser, appPassword, imapHost, imapPort, folderName)
            currentAppender = appender

            notifier.updatePhase("Checking what's already backed up…")
            val existingMessageIds = appender.fetchExistingMessageIds()
            if (cancelled) return

            notifier.updatePhase("Reading SMS history…")
            val items = mutableListOf<HistoryItem>()
            items.addAll(SmsReader.queryAll(context).map {
                HistoryItem.Sms(it.id, it.address, it.body, it.dateMillis, it.threadId, it.subId, it.isOutgoing)
            })
            if (cancelled) return

            notifier.updatePhase("Reading MMS history… this is the slow part, hang tight")
            items.addAll(MmsReader.queryAll(context) { mmsDone ->
                if (mmsDone % 50 == 0) notifier.updatePhase("Reading MMS history… ${mmsDone.grouped()} read so far")
            }.map { HistoryItem.Mms(it) })
            if (cancelled) return

            notifier.updatePhase("Reading RCS history…")
            items.addAll(ImChatReader.queryAll(context).map { HistoryItem.Rcs(it) })
            items.sortBy { it.dateMillis }

            val total = items.size
            var done = 0
            val threadReferences = mutableMapOf<String, MutableList<String>>()

            for (item in items) {
                if (cancelled) break

                val (eventType, localId, messageId, tokens) = when (item) {
                    is HistoryItem.Sms -> {
                        val mid = computeSmsMessageId(item.address, item.dateMillis, item.body, item.isOutgoing)
                        val tok = EventTokens.forSmsHistory(context, item.address, item.body, item.dateMillis, item.subId, item.isOutgoing)
                        TokenizedItem(BackupTracker.TYPE_SMS, item.id, mid, tok)
                    }
                    is HistoryItem.Mms -> {
                        val e = item.entry
                        val body = if (e.attachmentCount > 0) {
                            val noun = if (e.attachmentCount == 1) "attachment" else "attachments"
                            (e.textBody.ifBlank { "" } + "\n\n[${e.attachmentCount} $noun not included]").trim()
                        } else e.textBody
                        val mid = computeMmsMessageId(e.id, e.counterpart, e.dateMillis, e.isOutgoing, body)
                        val tok = EventTokens.forSmsHistory(context, e.counterpart, body, e.dateMillis, e.subId, e.isOutgoing)
                        TokenizedItem(BackupTracker.TYPE_MMS, e.id, mid, tok)
                    }
                    is HistoryItem.Rcs -> {
                        val e = item.entry
                        val mid = computeRcsMessageId(e.id, e.address, e.dateMillis, e.isOutgoing, e.body)
                        val tok = EventTokens.forSmsHistory(context, e.address, e.body, e.dateMillis, e.subId, e.isOutgoing)
                        TokenizedItem(BackupTracker.TYPE_RCS, e.id, mid, tok)
                    }
                }

                val alreadyDone = tracker.isImported(eventType, localId, gmailUser) || existingMessageIds.contains(messageId)
                if (!alreadyDone) {
                    val from = TemplateEngine.render(template.from, tokens)
                    val fromName = TemplateEngine.render(template.fromName, tokens)
                    val to = TemplateEngine.render(template.to, tokens).ifBlank { gmailUser }
                    val cc = TemplateEngine.render(template.cc, tokens)
                    val bcc = TemplateEngine.render(template.bcc, tokens)
                    val subject = TemplateEngine.render(template.subject, tokens)
                    val renderedBody = TemplateEngine.render(template.body, tokens)

                    val priorIds = threadReferences.getOrPut(item.threadKey) { mutableListOf() }
                    val inReplyTo = priorIds.lastOrNull()
                    val references = if (priorIds.isNotEmpty()) priorIds.joinToString(" ") else null

                    val sent = appender.append(
                        from, fromName, to, cc, bcc, subject, renderedBody,
                        messageId, Date(item.dateMillis), inReplyTo, references,
                        MAX_ATTEMPTS_PER_MESSAGE
                    ) { cancelled }

                    if (sent) {
                        priorIds.add(messageId)
                        tracker.markImported(eventType, localId, gmailUser, messageId)
                    } else {
                        Log.e(TAG, "Giving up on $eventType $localId - will retry on next run")
                    }
                    Thread.sleep(PACE_DELAY_MS)
                } else {
                    threadReferences.getOrPut(item.threadKey) { mutableListOf() }.add(messageId)
                }

                done++
                if (done % 20 == 0) notifier.update(done, total)
            }

            notifier.update(done, total, finished = true)
        } catch (e: Exception) {
            Log.e(TAG, "Backup run failed", e)
            val count = tracker.importedCount(BackupTracker.TYPE_SMS, gmailUser) +
                tracker.importedCount(BackupTracker.TYPE_MMS, gmailUser) +
                tracker.importedCount(BackupTracker.TYPE_RCS, gmailUser)
            notifier.update(count, count, finished = true)
        } finally {
            appender?.close()
            currentAppender = null
            running = false
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private data class TokenizedItem(val eventType: String, val localId: Long, val messageId: String, val tokens: Map<String, String>)

    private fun computeSmsMessageId(address: String, dateMillis: Long, body: String, isOutgoing: Boolean): String {
        val raw = "$address|$dateMillis|${if (isOutgoing) 2 else 1}|$body"
        return "<sms-${sha256(raw)}@mailmysms.local>"
    }

    private fun computeMmsMessageId(mmsId: Long, counterpart: String, dateMillis: Long, isOutgoing: Boolean, body: String): String {
        val raw = "$mmsId|$counterpart|$dateMillis|${if (isOutgoing) 2 else 1}|$body"
        return "<mms-${sha256(raw)}@mailmysms.local>"
    }

    private fun computeRcsMessageId(rcsId: Long, address: String, dateMillis: Long, isOutgoing: Boolean, body: String): String {
        val raw = "$rcsId|$address|$dateMillis|${if (isOutgoing) 2 else 1}|$body"
        return "<rcs-${sha256(raw)}@mailmysms.local>"
    }

    private fun sha256(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "SmsBackupService"
        private const val NOTIFICATION_ID = 43
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
            context.startForegroundService(Intent(context, SmsBackupService::class.java))
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
