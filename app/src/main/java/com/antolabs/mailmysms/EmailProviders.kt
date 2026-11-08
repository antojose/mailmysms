package com.antolabs.mailmysms

data class EmailProviderPreset(
    val label: String,
    val host: String,
    val port: Int,
    val instructions: String
)

object EmailProviders {
    val GMAIL = EmailProviderPreset(
        "Gmail",
        "imap.gmail.com",
        993,
        "1. Turn on 2-Step Verification at myaccount.google.com/security\n" +
            "2. Create an App Password at myaccount.google.com/apppasswords\n" +
            "3. In Gmail settings → Forwarding and POP/IMAP, make sure IMAP access is enabled"
    )

    val OUTLOOK = EmailProviderPreset(
        "Outlook / Office 365 / Hotmail",
        "outlook.office365.com",
        993,
        "1. Turn on 2-Step Verification at account.microsoft.com/security\n" +
            "2. Create an App Password at account.live.com/proofs/AppPassword\n" +
            "   (work/school accounts: ask your admin to allow app passwords)\n" +
            "3. IMAP access is on by default"
    )

    val YAHOO = EmailProviderPreset(
        "Yahoo Mail",
        "imap.mail.yahoo.com",
        993,
        "1. Turn on 2-Step Verification at login.yahoo.com/myaccount/security\n" +
            "2. Generate an App Password from the same security page\n" +
            "3. IMAP access is on by default"
    )

    val ICLOUD = EmailProviderPreset(
        "iCloud Mail",
        "imap.mail.me.com",
        993,
        "1. Turn on Two-Factor Authentication for your Apple ID\n" +
            "2. Go to appleid.apple.com → Sign-In and Security → App-Specific Passwords\n" +
            "3. IMAP access is on by default for iCloud Mail"
    )

    val CUSTOM = EmailProviderPreset(
        "Other (enter your own IMAP server)",
        "",
        993,
        "Enter your provider's IMAP host and port yourself (often 993 for IMAP over SSL). " +
            "Check whether it needs an app-specific password and that IMAP access is enabled."
    )

    val all = listOf(GMAIL, OUTLOOK, YAHOO, ICLOUD, CUSTOM)

    fun matching(host: String): EmailProviderPreset = all.firstOrNull { it.host == host } ?: CUSTOM
}
