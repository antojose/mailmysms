package com.antolabs.mailmysms

data class MessageTemplate(
    val from: String,
    val fromName: String,
    val to: String,
    val cc: String,
    val bcc: String,
    val subject: String,
    val body: String
) {
    companion object {
        fun defaultSms() = MessageTemplate(
            from = "{sender_number_safe}@mailmysms.local",
            fromName = "{sender_name}",
            to = "{account_email}",
            cc = "",
            bcc = "",
            subject = "{tag_bracket}SMS from {sender_name}",
            body = "From: {sender_display}\n" +
                "SIM: {sim_label}\n" +
                "Received on: {received_number} ({received_number_label})\n" +
                "Device: {device_label_or_unlabeled}\n" +
                "Time: {received_time}\n\n" +
                "{message_body}"
        )

        fun defaultCall() = MessageTemplate(
            from = "{caller_number_safe}@mailmysms.local",
            fromName = "{caller_name}",
            to = "{account_email}",
            cc = "",
            bcc = "",
            subject = "{tag_bracket}{call_type} call: {caller_name}",
            body = "Type: {call_type}\n" +
                "Number: {caller_display}\n" +
                "SIM: {sim_label}\n" +
                "Received on: {received_number} ({received_number_label})\n" +
                "Device: {device_label_or_unlabeled}\n" +
                "Time: {received_time}\n" +
                "Duration: {call_duration}s"
        )
    }
}
