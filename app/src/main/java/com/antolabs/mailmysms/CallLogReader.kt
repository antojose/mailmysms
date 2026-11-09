package com.antolabs.mailmysms

import android.content.Context
import android.provider.CallLog

data class CallRecord(
    val id: Long,
    val number: String,
    val type: Int,
    val dateMillis: Long,
    val durationSeconds: Long,
    val phoneAccountId: String?
)

object CallLogReader {
    fun queryAll(context: Context): List<CallRecord> {
        val result = mutableListOf<CallRecord>()
        val projection = arrayOf(
            "_id", CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE,
            CallLog.Calls.DURATION, CallLog.Calls.PHONE_ACCOUNT_ID
        )
        val cursor = context.contentResolver.query(CallLog.Calls.CONTENT_URI, projection, null, null, null)
            ?: return result

        cursor.use {
            val idIdx = it.getColumnIndexOrThrow("_id")
            val numberIdx = it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val typeIdx = it.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val dateIdx = it.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val durationIdx = it.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            val phoneAccountIdx = it.getColumnIndexOrThrow(CallLog.Calls.PHONE_ACCOUNT_ID)
            while (it.moveToNext()) {
                result.add(
                    CallRecord(
                        id = it.getLong(idIdx),
                        number = it.getString(numberIdx) ?: "Unknown",
                        type = it.getInt(typeIdx),
                        dateMillis = it.getLong(dateIdx),
                        durationSeconds = it.getLong(durationIdx),
                        phoneAccountId = it.getString(phoneAccountIdx)
                    )
                )
            }
        }
        return result
    }

    fun typeLabel(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "Incoming"
        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
        CallLog.Calls.MISSED_TYPE -> "Missed"
        CallLog.Calls.REJECTED_TYPE -> "Rejected"
        CallLog.Calls.BLOCKED_TYPE -> "Blocked"
        else -> "Call"
    }
}
