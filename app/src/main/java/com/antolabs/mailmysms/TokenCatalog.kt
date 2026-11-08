package com.antolabs.mailmysms

object TokenCatalog {
    data class Token(val name: String, val description: String, val example: String)

    private val shared = listOf(
        Token("app_name", "The app's name.", "MailMySMS"),
        Token("account_email", "Your configured email account.", "you@gmail.com"),
        Token("device_label", "The device label you set in settings. Empty if you haven't set one.", "A52s"),
        Token("device_label_or_unlabeled", "Device label, or \"(unlabeled)\" if you haven't set one.", "A52s"),
        Token("sim_slot", "Which physical SIM slot the event came in on, or \"SIM ?\" if it can't be resolved.", "SIM1"),
        Token("sim_carrier", "Carrier name of the receiving SIM, if Android reports one.", "Jio"),
        Token("sim_label", "Slot + carrier combined.", "SIM1 (Jio)"),
        Token("received_number", "The phone number you manually entered for that SIM slot in settings.", "+9190XXXXXXXX"),
        Token("received_number_label", "The label you manually entered for that SIM slot in settings.", "Personal"),
        Token("received_time", "Date and time the SMS/call was received.", "2026-11-08 15:07:32"),
        Token("received_date", "Date only.", "2026-11-08"),
        Token("tag", "Device label + SIM label joined together, for use in your own custom text.", "A52s - SIM1 (Jio)"),
        Token("tag_bracket", "Same as {tag} but pre-wrapped as \"[...] \" - empty string if there's nothing to show.", "[A52s - SIM1 (Jio)] ")
    )

    private val sms = listOf(
        Token("sender_number", "Raw sender address - a phone number, or an alphanumeric ID like a bank's.", "+911234567890 or AX-SBIINB-S"),
        Token("sender_number_safe", "Sender address stripped to only letters/digits/+/-/. - safe for an email address's local part.", "+911234567890"),
        Token("sender_name", "Contact name if the number matches someone in Contacts, else falls back to the raw number.", "John Doe"),
        Token("sender_display", "\"Name <number>\" when a contact matched, otherwise just the number.", "John Doe <+911234567890>"),
        Token("message_body", "The SMS text itself.", "Your OTP is 482913")
    )

    private val call = listOf(
        Token("caller_number", "Raw phone number from the call log entry.", "+911234567890"),
        Token("caller_number_safe", "Number stripped to only letters/digits/+/-/. - safe for an email address's local part.", "+911234567890"),
        Token("caller_name", "Contact name if the number matches someone in Contacts, else falls back to the raw number.", "John Doe"),
        Token("caller_display", "\"Name <number>\" when a contact matched, otherwise just the number.", "John Doe <+911234567890>"),
        Token("call_type", "Incoming, Outgoing, Missed, Rejected, or Blocked.", "Missed"),
        Token("call_duration", "Call duration in whole seconds.", "47")
    )

    val missingSenderNetworkNote =
        "There is no on-device way to look up the SENDER's network/carrier for an arbitrary " +
            "incoming number (that needs a paid lookup service), so no such token exists."

    fun forType(type: String): List<Token> = shared + if (type == TemplateActivity.TYPE_SMS) sms else call
}
