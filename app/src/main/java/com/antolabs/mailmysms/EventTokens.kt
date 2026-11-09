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
        // A stored subscriptionId only resolves to a slot/carrier if that exact subscription is
        // still active on the device today - Android doesn't keep a historical mapping, and it
        // changes whenever a SIM is swapped. So for an old message (especially after a SIM
        // change), the slot/carrier legitimately can't be known - but the raw subscriptionId (or
        // phoneAccountId, for calls) is still sitting right there in the record, and even though
        // it can't be mapped to "SIM1" or a carrier name, it's a stable value that lets the user
        // tell two different historical SIMs apart from each other, so it's worth keeping rather
        // than discarding in favor of a bare "unknown".
        val rawSimId = subId?.toString() ?: phoneAccountId
        val unresolvedSuffix = rawSimId?.let { " (raw SIM ID $it, older message, or SIM has changed since)" }
            ?: " (older message, or SIM has changed since)"
        val simSlotLabel = simInfo?.let { "SIM${it.slotIndex + 1}" } ?: "unknown"
        val simCarrier = simInfo?.carrierName?.trim().orEmpty()
        val simLabel = simInfo?.let {
            "SIM${it.slotIndex + 1}" + if (simCarrier.isNotBlank()) " ($simCarrier)" else ""
        } ?: "unknown$unresolvedSuffix"
        val receivedNumber = simInfo?.let { SettingsStore.simNumber(context, it.slotIndex) }.orEmpty()
            .ifBlank { "unknown number" }
        val receivedNumberLabel = simInfo?.let { SettingsStore.simLabel(context, it.slotIndex) }.orEmpty()
            .ifBlank { "unlabeled" }
        // Subject-line tag stays short even when SIM is unknown - no point putting the long
        // explanatory fallback text into every email subject.
        val tag = listOf(deviceLabel, simInfo?.let { simLabel })
            .filterNot { it.isNullOrBlank() }
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
            "received_number" to receivedNumber,
            "received_number_label" to receivedNumberLabel,
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

    /**
     * For the bulk SMS backup, which covers both incoming and outgoing messages. "sender_*"
     * tokens resolve to "Me" for outgoing so existing SMS templates (built incoming-only) still
     * read sensibly either way; "counterpart_*" tokens always mean "the other party" regardless
     * of direction, for templates that want to phrase things direction-neutrally.
     */
    fun forSmsHistory(
        context: Context,
        counterpartNumber: String,
        body: String,
        timestampMillis: Long,
        subId: Int?,
        isOutgoing: Boolean
    ): Map<String, String> {
        val tokens = common(context, timestampMillis, subId, null)
        val contactName = ContactUtils.lookupName(context, counterpartNumber)
        val counterpartName = contactName ?: counterpartNumber
        val counterpartDisplay = if (contactName != null) "$contactName <$counterpartNumber>" else counterpartNumber

        tokens["direction"] = if (isOutgoing) "Outgoing" else "Incoming"
        tokens["counterpart_number"] = counterpartNumber
        tokens["counterpart_number_safe"] = PhoneUtils.emailLocalPart(counterpartNumber)
        tokens["counterpart_name"] = counterpartName
        tokens["counterpart_display"] = counterpartDisplay
        tokens["message_body"] = body

        if (isOutgoing) {
            tokens["sender_number"] = "Me"
            tokens["sender_number_safe"] = "me"
            tokens["sender_name"] = "Me"
            tokens["sender_display"] = "Me"
        } else {
            tokens["sender_number"] = counterpartNumber
            tokens["sender_number_safe"] = PhoneUtils.emailLocalPart(counterpartNumber)
            tokens["sender_name"] = counterpartName
            tokens["sender_display"] = counterpartDisplay
        }
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
