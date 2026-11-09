package com.antolabs.mailmysms

import android.content.Context
import android.provider.Telephony

data class SmsRecord(
    val id: Long,
    val address: String,
    val body: String,
    val dateMillis: Long,
    val threadId: Long,
    val subId: Int?,
    val isOutgoing: Boolean
)

object SmsReader {
    fun queryAll(context: Context): List<SmsRecord> {
        val result = mutableListOf<SmsRecord>()
        val projection = arrayOf(
            "_id", Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE,
            Telephony.Sms.TYPE, Telephony.Sms.THREAD_ID, Telephony.Sms.SUBSCRIPTION_ID
        )
        val selection = "${Telephony.Sms.TYPE} IN (${Telephony.Sms.MESSAGE_TYPE_INBOX}, ${Telephony.Sms.MESSAGE_TYPE_SENT})"
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, projection, selection, null, null
        ) ?: return result

        cursor.use {
            val idIdx = it.getColumnIndexOrThrow("_id")
            val addressIdx = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val typeIdx = it.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val threadIdx = it.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val subIdx = it.getColumnIndexOrThrow(Telephony.Sms.SUBSCRIPTION_ID)

            while (it.moveToNext()) {
                val type = it.getInt(typeIdx)
                result.add(
                    SmsRecord(
                        id = it.getLong(idIdx),
                        address = it.getString(addressIdx) ?: "Unknown",
                        body = it.getString(bodyIdx) ?: "",
                        dateMillis = it.getLong(dateIdx),
                        threadId = it.getLong(threadIdx),
                        subId = it.getInt(subIdx).let { s -> if (s < 0) null else s },
                        isOutgoing = type == Telephony.Sms.MESSAGE_TYPE_SENT
                    )
                )
            }
        }
        return result
    }
}
