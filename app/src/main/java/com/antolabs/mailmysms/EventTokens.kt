package com.antolabs.mailmysms

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object EventTokens {

    private fun common(
        context: Context,
        timestampMillis: Long,
        subId: Int?,
        phoneAccountId: String?
    ): MutableMap<String, String> {
        val deviceLabel = SettingsStore.deviceLabel(context)
        val simInfo = when {
            subId != null -> SimUtils.infoForSubscriptionId(context, subId)
            phoneAccountId != null -> SimUtils.infoForPhoneAccountId(context, phoneAccountId)
            else -> null
        }
        val simSlotLabel = simInfo?.let { "SIM${it.slotIndex + 1}" } ?: "SIM ?"
        val simCarrier = simInfo?.carrierName?.trim().orEmpty()
        val simLabel = simInfo?.let {
            "SIM${it.slotIndex + 1}" + if (simCarrier.isNotBlank()) " ($simCarrier)" else ""
        } ?: "SIM ?"
        val receivedNumber = simInfo?.let { SettingsStore.simNumber(context, it.slotIndex) }.orEmpty()
        val receivedNumberLabel = simInfo?.let { SettingsStore.simLabel(context, it.slotIndex) }.orEmpty()
        val tag = listOf(deviceLabel, simLabel)
            .filter { it.isNotBlank() && it != "SIM ?" }
            .joinToString(" - ")

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestampMillis))
        val dateOnly = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestampMillis))

        return mutableMapOf(
            "app_name" to "MailMySMS",
            "account_email" to SettingsStore.emailUser(context),
            "device_label" to deviceLabel,
            "device_label_or_unlabeled" to deviceLabel.ifBlank { "(unlabeled)" },
            "sim_slot" to simSlotLabel,
            "sim_carrier" to simCarrier,
            "sim_label" to simLabel,
            "received_number" to receivedNumber.ifBlank { "(not set)" },
            "received_number_label" to receivedNumberLabel.ifBlank { "(not set)" },
            "received_time" to timestamp,
            "received_date" to dateOnly,
            "tag" to tag,
            "tag_bracket" to if (tag.isNotBlank()) "[$tag] " else ""
        )
    }

    fun forSms(context: Context, sender: String, body: String, timestampMillis: Long, subId: Int?): Map<String, String> {
        val tokens = common(context, timestampMillis, subId, null)
        val contactName = ContactUtils.lookupName(context, sender)
        tokens["sender_number"] = sender
        tokens["sender_number_safe"] = PhoneUtils.emailLocalPart(sender)
        tokens["sender_name"] = contactName ?: sender
        tokens["sender_display"] = if (contactName != null) "$contactName <$sender>" else sender
        tokens["message_body"] = body
        return tokens
    }

    fun forCall(
        context: Context,
        number: String,
        callType: String,
        durationSeconds: Long,
        timestampMillis: Long,
        phoneAccountId: String?
    ): Map<String, String> {
        val tokens = common(context, timestampMillis, null, phoneAccountId)
        val contactName = ContactUtils.lookupName(context, number)
        tokens["caller_number"] = number
        tokens["caller_number_safe"] = PhoneUtils.emailLocalPart(number)
        tokens["caller_name"] = contactName ?: number
        tokens["caller_display"] = if (contactName != null) "$contactName <$number>" else number
        tokens["call_type"] = callType
        tokens["call_duration"] = durationSeconds.toString()
        return tokens
    }
}
