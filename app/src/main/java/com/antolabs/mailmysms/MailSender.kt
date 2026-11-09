package com.antolabs.mailmysms

import java.util.Date
import java.util.Properties
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Session
import javax.mail.Store
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

object MailSender {
    /**
     * Inserts a message directly into the account's Gmail INBOX over IMAP
     * (no SMTP relay), so header values are free-form template output rather
     * than constrained by SMTP's anti-spoofing / authenticated-sender rules.
     * To/Cc/Bcc are therefore cosmetic header text only - nothing is actually
     * routed to those addresses. Used for single live events.
     */
    fun append(
        emailUser: String,
        appPassword: String,
        imapHost: String,
        imapPort: Int,
        fromAddress: String,
        fromDisplayName: String,
        to: String,
        cc: String,
        bcc: String,
        subject: String,
        body: String
    ) {
        val session = openSession(emailUser, appPassword, imapHost, imapPort)
        try {
            val folder = session.openFolder("INBOX")
            try {
                appendToFolder(session, folder, fromAddress, fromDisplayName, to, cc, bcc, subject, body, null, Date(), null, null)
            } finally {
                folder.close(false)
            }
        } finally {
            session.close()
        }
    }

    /** A connected IMAP session, reused across many appends for bulk operations. */
    class MailSession(val javaMailSession: Session, val store: Store) {
        fun openFolder(name: String): Folder {
            val folder = store.getFolder(name)
            if (!folder.exists()) folder.create(Folder.HOLDS_MESSAGES)
            folder.open(Folder.READ_WRITE)
            return folder
        }

        fun close() {
            store.close()
        }
    }

    fun openSession(emailUser: String, appPassword: String, imapHost: String, imapPort: Int): MailSession {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", imapHost)
            put("mail.imaps.port", imapPort.toString())
            // Without these, a dropped/stalled connection (e.g. the network changing while the
            // screen is off mid bulk-import) blocks forever with no exception and no log output,
            // rather than failing so the caller can reconnect and retry.
            put("mail.imaps.connectiontimeout", "30000")
            put("mail.imaps.timeout", "30000")
            put("mail.imaps.writetimeout", "30000")
        }
        val javaMailSession = Session.getInstance(props)
        val store = javaMailSession.getStore("imaps")
        store.connect(imapHost, imapPort, emailUser, appPassword)
        return MailSession(javaMailSession, store)
    }

    /** Reads just the Message-ID header of every message in a folder, for dedup checking before a bulk import. */
    fun fetchExistingMessageIds(folder: Folder): Set<String> {
        val ids = mutableSetOf<String>()
        val count = folder.messageCount
        if (count == 0) return ids
        val messages = folder.getMessages(1, count)
        folder.fetch(messages, javax.mail.FetchProfile().apply { add("Message-ID") })
        for (m in messages) {
            (m as? MimeMessage)?.messageID?.let { ids.add(it) }
        }
        return ids
    }

    fun appendToFolder(
        session: MailSession,
        folder: Folder,
        fromAddress: String,
        fromDisplayName: String,
        to: String,
        cc: String,
        bcc: String,
        subject: String,
        body: String,
        messageId: String?,
        sentDate: Date,
        inReplyTo: String?,
        references: String?
    ) {
        val message = DatedMimeMessage(session.javaMailSession, sentDate).apply {
            setFrom(InternetAddress(fromAddress, fromDisplayName, "UTF-8"))
            if (to.isNotBlank()) setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
            if (cc.isNotBlank()) setRecipients(Message.RecipientType.CC, InternetAddress.parse(cc))
            if (bcc.isNotBlank()) setRecipients(Message.RecipientType.BCC, InternetAddress.parse(bcc))
            setSubject(subject)
            setText(body)
            setSentDate(sentDate)
            if (inReplyTo != null) setHeader("In-Reply-To", inReplyTo)
            if (references != null) setHeader("References", references)
            if (messageId != null) {
                setHeader("Message-ID", messageId)
            }
            saveChanges()
        }
        folder.appendMessages(arrayOf(message))
    }

    /**
     * Plain MimeMessage.getReceivedDate() always returns null, which makes the IMAP server
     * stamp the APPEND with "now" as its internal date - and Gmail sorts/lists messages by
     * that internal date, not by the Date header. Overriding it here is what makes a
     * backed-up SMS from years ago actually show up at its original date in Gmail's UI.
     */
    private class DatedMimeMessage(session: Session, private val received: Date) : MimeMessage(session) {
        override fun getReceivedDate(): Date = received
    }
}
