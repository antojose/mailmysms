package com.antolabs.mailmysms

import android.content.Context
import android.net.Uri
import android.util.Log

data class RcsEntry(
    val id: Long,
    val address: String,
    val dateMillis: Long,
    val threadId: Long,
    val subId: Int?,
    val isOutgoing: Boolean,
    val body: String
)

/**
 * RCS chat messages (including RCS Business Messaging from banks/merchants, and regular
 * person-to-person RCS chats) are not stored in the standard SMS/MMS providers at all - Android
 * keeps them in a separate, legacy "Instant Messaging" provider (content://im/chat for text,
 * content://im/ft for file-transfer/rich-card messages) that's still gated by the same
 * READ_SMS permission, just never queried by most apps. Confirmed via direct inspection of
 * com.android.providers.telephony's manifest: ImProvider declares
 * readPermission="android.permission.READ_SMS", exported=true.
 */
data class ImChatQueryResult(val entries: List<RcsEntry>, val omittedSystemEvents: Int)

object ImChatReader {
    private const val TAG = "ImChatReader"
    private val CHAT_URI = Uri.parse("content://im/chat")
    private val FT_URI = Uri.parse("content://im/ft")

    /** Convenience wrapper for callers that only need the real messages, not the omitted count. */
    fun queryAll(context: Context): List<RcsEntry> = queryAllWithOmitted(context).entries

    fun queryAllWithOmitted(context: Context): ImChatQueryResult {
        val (chatEntries, chatOmitted) = queryChat(context)
        val (ftEntries, ftOmitted) = queryFileTransfers(context)
        return ImChatQueryResult(chatEntries + ftEntries, chatOmitted + ftOmitted)
    }

    private fun queryChat(context: Context): Pair<List<RcsEntry>, Int> {
        val result = mutableListOf<RcsEntry>()
        var omitted = 0
        try {
            context.contentResolver.query(
                CHAT_URI, arrayOf("_id", "thread_id", "address", "date", "type", "body", "sub_id"), null, null, null
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow("_id")
                val threadIdx = c.getColumnIndexOrThrow("thread_id")
                val addrIdx = c.getColumnIndexOrThrow("address")
                val dateIdx = c.getColumnIndexOrThrow("date")
                val typeIdx = c.getColumnIndexOrThrow("type")
                val bodyIdx = c.getColumnIndexOrThrow("body")
                val subIdx = c.getColumnIndexOrThrow("sub_id")
                while (c.moveToNext()) {
                    // A null address means a conversation-level system event (e.g. "Everyone has
                    // left this chat"), not a message from anyone - every real message has one.
                    val address = c.getString(addrIdx)?.removePrefix("sip:")
                    if (address == null) {
                        omitted++
                        continue
                    }
                    result.add(
                        RcsEntry(
                            id = c.getLong(idIdx),
                            address = address,
                            dateMillis = c.getLong(dateIdx),
                            threadId = c.getLong(threadIdx),
                            subId = c.getInt(subIdx).let { s -> if (s < 0) null else s },
                            isOutgoing = c.getInt(typeIdx) == 2,
                            body = c.getString(bodyIdx) ?: ""
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query im/chat", e)
        }
        return result to omitted
    }

    private fun queryFileTransfers(context: Context): Pair<List<RcsEntry>, Int> {
        val result = mutableListOf<RcsEntry>()
        var omitted = 0
        try {
            context.contentResolver.query(
                FT_URI, arrayOf("_id", "thread_id", "address", "date", "type", "content_type", "sub_id"), null, null, null
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow("_id")
                val threadIdx = c.getColumnIndexOrThrow("thread_id")
                val addrIdx = c.getColumnIndexOrThrow("address")
                val dateIdx = c.getColumnIndexOrThrow("date")
                val typeIdx = c.getColumnIndexOrThrow("type")
                val subIdx = c.getColumnIndexOrThrow("sub_id")
                while (c.moveToNext()) {
                    val address = c.getString(addrIdx)?.removePrefix("sip:")
                    if (address == null) {
                        omitted++
                        continue
                    }
                    // These are rich cards / file attachments (e.g. application/vnd.gsma.botmessage),
                    // not plain text - there's no readable body, so this is represented the same way
                    // an MMS attachment is: text-only with a note, no binary content included.
                    result.add(
                        RcsEntry(
                            id = c.getLong(idIdx),
                            address = address,
                            dateMillis = c.getLong(dateIdx),
                            threadId = c.getLong(threadIdx),
                            subId = c.getInt(subIdx).let { s -> if (s < 0) null else s },
                            isOutgoing = c.getInt(typeIdx) == 2,
                            body = "[Rich card / file attachment message - content not included]"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query im/ft", e)
        }
        return result to omitted
    }
}
