package com.antolabs.mailmysms

import java.util.Date
import java.util.Properties
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

object MailSender {
    /**
     * Inserts a message directly into the account's Gmail INBOX over IMAP
     * (no SMTP relay), so header values are free-form template output rather
     * than constrained by SMTP's anti-spoofing / authenticated-sender rules.
     * To/Cc/Bcc are therefore cosmetic header text only - nothing is actually
     * routed to those addresses.
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
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", imapHost)
            put("mail.imaps.port", imapPort.toString())
        }
        val session = Session.getInstance(props)
        val store = session.getStore("imaps")
        store.connect(imapHost, imapPort, emailUser, appPassword)
        try {
            val folder = store.getFolder("INBOX")
            folder.open(Folder.READ_WRITE)
            try {
                val message = MimeMessage(session).apply {
                    setFrom(InternetAddress(fromAddress, fromDisplayName, "UTF-8"))
                    if (to.isNotBlank()) setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
                    if (cc.isNotBlank()) setRecipients(Message.RecipientType.CC, InternetAddress.parse(cc))
                    if (bcc.isNotBlank()) setRecipients(Message.RecipientType.BCC, InternetAddress.parse(bcc))
                    setSubject(subject)
                    setText(body)
                    setSentDate(Date())
                }
                folder.appendMessages(arrayOf(message))
            } finally {
                folder.close(false)
            }
        } finally {
            store.close()
        }
    }
}
