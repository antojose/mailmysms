package com.antolabs.mailmysms

import android.util.Log
import java.util.Date
import javax.mail.Folder

/**
 * Wraps a live IMAP session for a long bulk-append run (thousands of messages over possibly
 * hours): if a single append throws (dropped connection, timeout, transient server error), this
 * reconnects and retries rather than taking down the whole run. Shared by every bulk backup
 * service (SMS/MMS, call log, ...) so the retry/reconnect behavior only needs to be right once.
 */
class ResilientAppender(
    private val tag: String,
    private val emailUser: String,
    private val appPassword: String,
    private val imapHost: String,
    private val imapPort: Int,
    private val folderName: String
) {
    private var session: MailSender.MailSession = MailSender.openSession(emailUser, appPassword, imapHost, imapPort)
    private var folder: Folder = session.openFolder(folderName)

    fun fetchExistingMessageIds(): Set<String> = try {
        MailSender.fetchExistingMessageIds(folder)
    } catch (e: Exception) {
        Log.e(tag, "Failed to fetch existing message IDs, continuing with local tracking only", e)
        emptySet()
    }

    /** Returns true if the message was appended (possibly after reconnecting), false if every attempt failed. */
    fun append(
        from: String,
        fromName: String,
        to: String,
        cc: String,
        bcc: String,
        subject: String,
        body: String,
        messageId: String,
        sentDate: Date,
        inReplyTo: String?,
        references: String?,
        maxAttempts: Int,
        isCancelled: () -> Boolean
    ): Boolean {
        var attempt = 0
        while (attempt < maxAttempts && !isCancelled()) {
            attempt++
            try {
                MailSender.appendToFolder(
                    session, folder, from, fromName, to, cc, bcc, subject, body,
                    messageId, sentDate, inReplyTo, references
                )
                return true
            } catch (e: Exception) {
                Log.e(tag, "Append failed for $messageId (attempt $attempt), reconnecting", e)
                reconnect()
            }
        }
        return false
    }

    private fun reconnect() {
        try {
            session.close()
        } catch (e: Exception) {
            Log.e(tag, "Failed to close stale session", e)
        }
        try {
            session = MailSender.openSession(emailUser, appPassword, imapHost, imapPort)
            folder = session.openFolder(folderName)
        } catch (e: Exception) {
            Log.e(tag, "Reconnect failed", e)
            Thread.sleep(RECONNECT_BACKOFF_MS)
        }
    }

    fun close() {
        try {
            session.close()
        } catch (e: Exception) {
            Log.e(tag, "Failed to close mail session", e)
        }
    }

    companion object {
        private const val RECONNECT_BACKOFF_MS = 5000L
    }
}
