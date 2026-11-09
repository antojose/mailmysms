package com.antolabs.mailmysms

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Telephony
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ExportedFile(val name: String, val sizeBytes: Long)

data class LocalExportResult(
    val files: List<ExportedFile>,
    val messageCount: Int,
    val callCount: Int,
    val omittedCount: Int,
    val omittedReason: String
)

/**
 * A one-time local snapshot of SMS+MMS+RCS and call log history, saved as files on this device
 * (Download/MailMySMS-Exports) - distinct from the email backup, which sends everything to your
 * mailbox over IMAP. This writes nothing anywhere except local device storage.
 */
object LocalExporter {
    private const val EXPORT_SUBDIR = "MailMySMS-Exports"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /** onProgress reports (done, total) across the whole export - reading plus writing. */
    fun exportAll(context: Context, onProgress: (Int, Int) -> Unit = { _, _ -> }): LocalExportResult {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())

        // A rough upfront total so progress is meaningful from the first callback - MMS reading
        // (one sub-query per message for addresses, another for parts) is by far the slowest
        // phase, so that's the one with real per-row granularity; the others are fast enough that
        // jumping the counter at phase boundaries reads fine.
        val smsSelection = "${Telephony.Sms.TYPE} IN (${Telephony.Sms.MESSAGE_TYPE_INBOX}, ${Telephony.Sms.MESSAGE_TYPE_SENT})"
        val smsCountEstimate = context.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf("_id"), smsSelection, null, null)?.use { it.count } ?: 0
        val mmsSelection = "${Telephony.Mms.MESSAGE_BOX} IN (${Telephony.Mms.MESSAGE_BOX_INBOX}, ${Telephony.Mms.MESSAGE_BOX_SENT})"
        val mmsCountEstimate = context.contentResolver.query(Telephony.Mms.CONTENT_URI, arrayOf("_id"), mmsSelection, null, null)?.use { it.count } ?: 0
        val callCountEstimate = context.contentResolver.query(android.provider.CallLog.Calls.CONTENT_URI, arrayOf("_id"), null, null, null)?.use { it.count } ?: 0
        val rcsCountEstimate = context.contentResolver.query(Uri.parse("content://im/chat"), arrayOf("_id"), null, null, null)?.use { it.count } ?: 0
        val writeSteps = 4 // the 4 output files, counted as one progress step each once written
        val total = (smsCountEstimate + mmsCountEstimate + callCountEstimate + rcsCountEstimate + writeSteps).coerceAtLeast(1)
        var done = 0

        val sms = SmsReader.queryAll(context)
        done += sms.size
        onProgress(done, total)

        val mms = MmsReader.queryAll(context) { mmsDone ->
            onProgress(done + mmsDone, total)
        }
        done += mms.size
        onProgress(done, total)

        val rcsResult = ImChatReader.queryAllWithOmitted(context)
        val rcs = rcsResult.entries
        done += rcs.size + rcsResult.omittedSystemEvents
        onProgress(done, total)

        val calls = CallLogReader.queryAll(context)
        done += calls.size
        onProgress(done, total)

        val messageCount = sms.size + mms.size + rcs.size
        val messagesXml = buildMessagesXml(sms, mms, rcs, messageCount)
        val messagesCsv = buildMessagesCsv(sms, mms, rcs)
        val callsXml = buildCallsXml(calls)
        val callsCsv = buildCallsCsv(calls)

        val messagesXmlName = "mailmysms-messages_$timestamp.xml"
        val messagesCsvName = "mailmysms-messages_$timestamp.csv"
        val callsXmlName = "mailmysms-calls_$timestamp.xml"
        val callsCsvName = "mailmysms-calls_$timestamp.csv"

        val files = listOfNotNull(
            writeFile(context, messagesXmlName, "text/xml", messagesXml).also { done++; onProgress(done, total) },
            writeFile(context, messagesCsvName, "text/csv", CsvUtils.UTF8_BOM + messagesCsv).also { done++; onProgress(done, total) },
            writeFile(context, callsXmlName, "text/xml", callsXml).also { done++; onProgress(done, total) },
            writeFile(context, callsCsvName, "text/csv", CsvUtils.UTF8_BOM + callsCsv).also { done++; onProgress(done, total) }
        )

        SettingsStore.saveLastLocalExport(context, messageCount, calls.size)

        return LocalExportResult(
            files, messageCount, calls.size, rcsResult.omittedSystemEvents,
            "RCS group-chat system notices (e.g. \"Everyone has left this chat\"), not real messages from anyone"
        )
    }

    private fun buildMessagesXml(sms: List<SmsRecord>, mms: List<MmsEntry>, rcs: List<RcsEntry>, total: Int): String {
        val sb = StringBuilder()
        sb.append("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n")
        sb.append("<!--File created by MailMySMS local export on ${dateFormat.format(Date())}-->\n")
        sb.append("<smses count=\"$total\">\n")
        for (r in sms) {
            sb.append("  <sms address=\"${XmlUtils.attr(r.address)}\" date=\"${r.dateMillis}\" ")
                .append("type=\"${if (r.isOutgoing) 2 else 1}\" body=\"${XmlUtils.attr(r.body)}\" ")
                .append("sub_id=\"${r.subId ?: -1}\" readable_date=\"${dateFormat.format(Date(r.dateMillis))}\" source=\"sms\" />\n")
        }
        for (e in mms) {
            val body = if (e.attachmentCount > 0) {
                val noun = if (e.attachmentCount == 1) "attachment" else "attachments"
                (e.textBody.ifBlank { "" } + " [${e.attachmentCount} $noun not included]").trim()
            } else e.textBody
            sb.append("  <mms address=\"${XmlUtils.attr(e.counterpart)}\" date=\"${e.dateMillis}\" ")
                .append("msg_box=\"${if (e.isOutgoing) 2 else 1}\" body=\"${XmlUtils.attr(body)}\" ")
                .append("sub_id=\"${e.subId ?: -1}\" readable_date=\"${dateFormat.format(Date(e.dateMillis))}\" source=\"mms\" />\n")
        }
        for (r in rcs) {
            sb.append("  <sms address=\"${XmlUtils.attr(r.address)}\" date=\"${r.dateMillis}\" ")
                .append("type=\"${if (r.isOutgoing) 2 else 1}\" body=\"${XmlUtils.attr(r.body)}\" ")
                .append("sub_id=\"${r.subId ?: -1}\" readable_date=\"${dateFormat.format(Date(r.dateMillis))}\" source=\"rcs\" />\n")
        }
        sb.append("</smses>\n")
        return sb.toString()
    }

    private fun buildMessagesCsv(sms: List<SmsRecord>, mms: List<MmsEntry>, rcs: List<RcsEntry>): String {
        val sb = StringBuilder()
        sb.append(CsvUtils.row(listOf("source", "direction", "address", "date_millis", "readable_date", "sub_id", "body"))).append("\n")
        for (r in sms) {
            sb.append(CsvUtils.row(listOf(
                "sms", if (r.isOutgoing) "Outgoing" else "Incoming", r.address, r.dateMillis.toString(),
                dateFormat.format(Date(r.dateMillis)), (r.subId ?: -1).toString(), r.body
            ))).append("\n")
        }
        for (e in mms) {
            val body = if (e.attachmentCount > 0) {
                val noun = if (e.attachmentCount == 1) "attachment" else "attachments"
                (e.textBody.ifBlank { "" } + " [${e.attachmentCount} $noun not included]").trim()
            } else e.textBody
            sb.append(CsvUtils.row(listOf(
                "mms", if (e.isOutgoing) "Outgoing" else "Incoming", e.counterpart, e.dateMillis.toString(),
                dateFormat.format(Date(e.dateMillis)), (e.subId ?: -1).toString(), body
            ))).append("\n")
        }
        for (r in rcs) {
            sb.append(CsvUtils.row(listOf(
                "rcs", if (r.isOutgoing) "Outgoing" else "Incoming", r.address, r.dateMillis.toString(),
                dateFormat.format(Date(r.dateMillis)), (r.subId ?: -1).toString(), r.body
            ))).append("\n")
        }
        return sb.toString()
    }

    private fun buildCallsXml(calls: List<CallRecord>): String {
        val sb = StringBuilder()
        sb.append("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n")
        sb.append("<!--File created by MailMySMS local export on ${dateFormat.format(Date())}-->\n")
        sb.append("<calls count=\"${calls.size}\">\n")
        for (c in calls) {
            sb.append("  <call number=\"${XmlUtils.attr(c.number)}\" date=\"${c.dateMillis}\" ")
                .append("duration=\"${c.durationSeconds}\" type=\"${c.type}\" ")
                .append("readable_date=\"${dateFormat.format(Date(c.dateMillis))}\" />\n")
        }
        sb.append("</calls>\n")
        return sb.toString()
    }

    private fun buildCallsCsv(calls: List<CallRecord>): String {
        val sb = StringBuilder()
        sb.append(CsvUtils.row(listOf("direction", "number", "date_millis", "readable_date", "duration_seconds"))).append("\n")
        for (c in calls) {
            sb.append(CsvUtils.row(listOf(
                CallLogReader.typeLabel(c.type), c.number, c.dateMillis.toString(),
                dateFormat.format(Date(c.dateMillis)), c.durationSeconds.toString()
            ))).append("\n")
        }
        return sb.toString()
    }

    private fun writeFile(context: Context, filename: String, mimeType: String, content: String): ExportedFile? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeViaMediaStore(context, filename, mimeType, content)
        } else {
            writeViaLegacyFile(context, filename, content)
        }
    }

    private fun writeViaMediaStore(context: Context, filename: String, mimeType: String, content: String): ExportedFile? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$EXPORT_SUBDIR")
        }
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        val bytes = content.toByteArray(Charsets.UTF_8)
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
        return ExportedFile(filename, bytes.size.toLong())
    }

    private fun writeViaLegacyFile(context: Context, filename: String, content: String): ExportedFile? {
        return try {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), EXPORT_SUBDIR)
            dir.mkdirs()
            val file = File(dir, filename)
            val bytes = content.toByteArray(Charsets.UTF_8)
            file.writeBytes(bytes)
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
            ExportedFile(filename, bytes.size.toLong())
        } catch (e: Exception) {
            null
        }
    }
}
