package com.antolabs.mailmysms

object PhoneUtils {
    private const val DOMAIN = "mailmysms.local"

    fun emailLocalPart(raw: String): String {
        val cleaned = raw.filter { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }
        return cleaned.ifBlank { "unknown" }
    }

    fun emailAddress(raw: String): String = "${emailLocalPart(raw)}@$DOMAIN"
}
