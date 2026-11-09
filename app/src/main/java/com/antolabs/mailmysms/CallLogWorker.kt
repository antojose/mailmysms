package com.antolabs.mailmysms

import android.content.Context
import android.provider.CallLog
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

class CallLogWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    override fun doWork(): Result {
        if (!SettingsStore.callsEnabled(applicationContext) || !SettingsStore.isConfigured(applicationContext)) {
            return Result.success()
        }

        return try {
            queryAndSend()
        } catch (e: Exception) {
            Log.e(TAG, "doWork failed", e)
            if (runAttemptCount >= MAX_ATTEMPTS) {
                val authFailure = e is javax.mail.AuthenticationFailedException
                EventLog.get(applicationContext).record("CALL", "Call log lookup failed: ${e.message}", false, authFailure = authFailure)
                Result.failure()
            } else {
                Result.retry()
            }
        }
    }

    private fun queryAndSend(): Result {
        val cursor = applicationContext.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.PHONE_ACCOUNT_ID
            ),
            null,
            null,
            "${CallLog.Calls.DATE} DESC"
        ) ?: return Result.retry()

        cursor.use {
            if (!it.moveToFirst()) return Result.success()

            val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)) ?: "Unknown"
            val type = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))
            val date = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))
            val duration = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DURATION))
            val phoneAccountId = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.PHONE_ACCOUNT_ID))

            val typeLabel = when (type) {
                CallLog.Calls.INCOMING_TYPE -> "Incoming"
                CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                CallLog.Calls.MISSED_TYPE -> "Missed"
                CallLog.Calls.REJECTED_TYPE -> "Rejected"
                CallLog.Calls.BLOCKED_TYPE -> "Blocked"
                else -> "Call"
            }

            val tokens = EventTokens.forCall(applicationContext, number, typeLabel, duration, date, phoneAccountId)
            val template = SettingsStore.callTemplate(applicationContext)
            val summary = "$typeLabel call: $number"

            TemplateMailer.send(applicationContext, template, tokens)
            EventLog.get(applicationContext).record("CALL", summary, true, date)
            return Result.success()
        }
    }

    companion object {
        private const val TAG = "CallLogWorker"
        private const val MAX_ATTEMPTS = 5
    }
}
