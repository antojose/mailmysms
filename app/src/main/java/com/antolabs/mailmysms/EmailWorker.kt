package com.antolabs.mailmysms

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Handles the SMS path: builds tokens from the raw SMS data, then renders + sends the SMS template. */
class EmailWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    override fun doWork(): Result {
        val sender = inputData.getString(KEY_SENDER) ?: return Result.failure()
        val body = inputData.getString(KEY_BODY) ?: ""
        val timestamp = inputData.getLong(KEY_TIMESTAMP, System.currentTimeMillis())
        val subId = inputData.getInt(KEY_SUB_ID, -1).let { if (it < 0) null else it }

        if (!SettingsStore.isConfigured(applicationContext)) return Result.failure()

        val tokens = EventTokens.forSms(applicationContext, sender, body, timestamp, subId)
        val template = SettingsStore.smsTemplate(applicationContext)
        val summary = "SMS from $sender"

        return try {
            TemplateMailer.send(applicationContext, template, tokens)
            EventLog.get(applicationContext).record("SMS", summary, true, timestamp)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount >= MAX_ATTEMPTS) {
                EventLog.get(applicationContext).record("SMS", summary, false, timestamp)
                Result.failure()
            } else {
                Result.retry()
            }
        }
    }

    companion object {
        const val KEY_SENDER = "sender"
        const val KEY_BODY = "body"
        const val KEY_TIMESTAMP = "timestamp"
        const val KEY_SUB_ID = "sub_id"
        private const val MAX_ATTEMPTS = 5

        fun enqueue(context: Context, sender: String, body: String, timestamp: Long, subId: Int) {
            val data = Data.Builder()
                .putString(KEY_SENDER, sender)
                .putString(KEY_BODY, body)
                .putLong(KEY_TIMESTAMP, timestamp)
                .putInt(KEY_SUB_ID, subId)
                .build()
            val request = OneTimeWorkRequestBuilder<EmailWorker>()
                .setInputData(data)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
