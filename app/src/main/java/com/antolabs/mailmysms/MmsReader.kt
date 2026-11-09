package com.antolabs.mailmysms

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log

data class MmsEntry(
    val id: Long,
    val dateMillis: Long,
    val threadId: Long,
    val subId: Int?,
    val isOutgoing: Boolean,
    val counterpart: String,
    val textBody: String,
    val attachmentCount: Int
)

/**
 * MMS is a separate Android content provider from plain SMS, with no single "address"/"body"
 * column: the counterpart lives in a per-message addr table, and the content lives in a
 * per-message part table (one row per text/image/video/etc. part). This reads just enough to
 * represent an MMS as a text-only backup item: the text parts concatenated, and a count of
 * whatever non-text parts (photos, etc.) were left out, per the "text only, note the attachment"
 * choice for this feature - no binary content is ever read here.
 */
object MmsReader {
    private const val TAG = "MmsReader"
    private const val ADDR_TYPE_FROM = 137
    private const val ADDR_TYPE_TO = 151

    fun queryAll(context: Context): List<MmsEntry> {
        val result = mutableListOf<MmsEntry>()
        val projection = arrayOf("_id", Telephony.Mms.DATE, Telephony.Mms.THREAD_ID, Telephony.Mms.SUBSCRIPTION_ID)
        val selection = "${Telephony.Mms.MESSAGE_BOX} IN (${Telephony.Mms.MESSAGE_BOX_INBOX}, ${Telephony.Mms.MESSAGE_BOX_SENT})"
        val cursor = context.contentResolver.query(
            Telephony.Mms.CONTENT_URI, projection, selection, null, "${Telephony.Mms.DATE} ASC"
        ) ?: return result

        cursor.use {
            val idIdx = it.getColumnIndexOrThrow("_id")
            val dateIdx = it.getColumnIndexOrThrow(Telephony.Mms.DATE)
            val threadIdx = it.getColumnIndexOrThrow(Telephony.Mms.THREAD_ID)
            val subIdx = it.getColumnIndexOrThrow(Telephony.Mms.SUBSCRIPTION_ID)

            while (it.moveToNext()) {
                val id = it.getLong(idIdx)
                // Mms.DATE is in seconds, unlike Sms.DATE which is in milliseconds.
                val dateMillis = it.getLong(dateIdx) * 1000L
                val threadId = it.getLong(threadIdx)
                val subId = it.getInt(subIdx).let { s -> if (s < 0) null else s }

                val addresses = readAddresses(context, id)
                val isOutgoing = addresses.fromIsMe
                val counterpart = if (isOutgoing) addresses.to.ifEmpty { listOf("Unknown") } else addresses.from.ifEmpty { listOf("Unknown") }

                val (textBody, attachmentCount) = readParts(context, id)

                result.add(
                    MmsEntry(
                        id = id,
                        dateMillis = dateMillis,
                        threadId = threadId,
                        subId = subId,
                        isOutgoing = isOutgoing,
                        counterpart = counterpart.joinToString(", "),
                        textBody = textBody,
                        attachmentCount = attachmentCount
                    )
                )
            }
        }
        return result
    }

    private data class Addresses(val from: List<String>, val to: List<String>, val fromIsMe: Boolean)

    private fun readAddresses(context: Context, mmsId: Long): Addresses {
        val from = mutableListOf<String>()
        val to = mutableListOf<String>()
        val uri = Uri.parse("content://mms/$mmsId/addr")
        try {
            context.contentResolver.query(uri, arrayOf("address", "type"), null, null, null)?.use { c ->
                val addrIdx = c.getColumnIndexOrThrow("address")
                val typeIdx = c.getColumnIndexOrThrow("type")
                while (c.moveToNext()) {
                    // RCS business-messaging addresses (e.g. "hdfc-bank-xyz@rbm.goog") are stored
                    // with a "sip:" prefix in this table that no other app or export includes -
                    // stripped here so it doesn't show up literally in the backup email.
                    val address = c.getString(addrIdx)?.removePrefix("sip:") ?: continue
                    when (c.getInt(typeIdx)) {
                        ADDR_TYPE_FROM -> from.add(address)
                        ADDR_TYPE_TO -> to.add(address)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read addresses for mms $mmsId", e)
        }
        // "insert-address-token" is what Android uses to mean "this device" as the sender of an outgoing MMS.
        val fromIsMe = from.any { it == "insert-address-token" }
        return Addresses(from.filterNot { it == "insert-address-token" }, to, fromIsMe)
    }

    private fun readParts(context: Context, mmsId: Long): Pair<String, Int> {
        val textParts = mutableListOf<String>()
        var attachmentCount = 0
        try {
            context.contentResolver.query(
                Uri.parse("content://mms/part"), arrayOf("_id", "ct", "text"), "mid = ?",
                arrayOf(mmsId.toString()), null
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow("_id")
                val ctIdx = c.getColumnIndexOrThrow("ct")
                val textIdx = c.getColumnIndexOrThrow("text")
                while (c.moveToNext()) {
                    val contentType = c.getString(ctIdx) ?: continue
                    if (contentType == "text/plain") {
                        val inlineText = c.getString(textIdx)
                        val text = inlineText ?: readPartBody(context, c.getLong(idIdx))
                        if (!text.isNullOrBlank()) textParts.add(text)
                    } else if (contentType != "application/smil") {
                        // smil is MMS's internal layout/timing descriptor, not user content - everything
                        // else (image/*, video/*, audio/*, ...) is a real attachment we're leaving out.
                        attachmentCount++
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read parts for mms $mmsId", e)
        }
        return textParts.joinToString("\n") to attachmentCount
    }

    private fun readPartBody(context: Context, partId: Long): String? {
        return try {
            val uri = ContentUris.withAppendedId(Uri.parse("content://mms/part"), partId)
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read part body $partId", e)
            null
        }
    }
}
