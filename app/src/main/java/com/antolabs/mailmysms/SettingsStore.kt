package com.antolabs.mailmysms

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SettingsStore {
    private const val FILE_NAME = "secure_settings"
    private const val KEY_GMAIL_USER = "gmail_user"
    private const val KEY_APP_PASSWORD = "gmail_app_password"
    private const val KEY_IMAP_HOST = "imap_host"
    private const val KEY_IMAP_PORT = "imap_port"
    private const val KEY_SMS_ENABLED = "sms_enabled"
    private const val KEY_CALLS_ENABLED = "calls_enabled"
    private const val KEY_DEVICE_LABEL = "device_label"
    private const val KEY_SMS_BACKUP_LABEL = "sms_backup_label"
    private const val DEFAULT_SMS_BACKUP_LABEL = "SMS Backup"
    private const val KEY_CALL_LOG_BACKUP_LABEL = "call_log_backup_label"
    private const val DEFAULT_CALL_LOG_BACKUP_LABEL = "Call Log Backup"

    private fun prefs(context: Context) = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveAccount(context: Context, emailUser: String, appPassword: String, imapHost: String, imapPort: Int) {
        prefs(context).edit()
            .putString(KEY_GMAIL_USER, emailUser)
            .putString(KEY_APP_PASSWORD, appPassword)
            .putString(KEY_IMAP_HOST, imapHost)
            .putInt(KEY_IMAP_PORT, imapPort)
            .apply()
    }

    fun saveGeneral(context: Context, smsEnabled: Boolean, callsEnabled: Boolean, deviceLabel: String) {
        prefs(context).edit()
            .putBoolean(KEY_SMS_ENABLED, smsEnabled)
            .putBoolean(KEY_CALLS_ENABLED, callsEnabled)
            .putString(KEY_DEVICE_LABEL, deviceLabel)
            .apply()
    }

    fun emailUser(context: Context): String = prefs(context).getString(KEY_GMAIL_USER, "") ?: ""
    fun appPassword(context: Context): String = prefs(context).getString(KEY_APP_PASSWORD, "") ?: ""
    fun imapHost(context: Context): String =
        prefs(context).getString(KEY_IMAP_HOST, EmailProviders.GMAIL.host) ?: EmailProviders.GMAIL.host
    fun imapPort(context: Context): Int = prefs(context).getInt(KEY_IMAP_PORT, EmailProviders.GMAIL.port)
    fun smsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_SMS_ENABLED, true)
    fun callsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_CALLS_ENABLED, true)
    fun deviceLabel(context: Context): String = prefs(context).getString(KEY_DEVICE_LABEL, "") ?: ""

    fun isConfigured(context: Context): Boolean =
        emailUser(context).isNotBlank() && appPassword(context).isNotBlank()

    fun smsBackupLabel(context: Context): String {
        val saved = prefs(context).getString(KEY_SMS_BACKUP_LABEL, "") ?: ""
        return saved.ifBlank { DEFAULT_SMS_BACKUP_LABEL }
    }

    fun saveSmsBackupLabel(context: Context, label: String) {
        prefs(context).edit().putString(KEY_SMS_BACKUP_LABEL, label).apply()
    }

    fun callLogBackupLabel(context: Context): String {
        val saved = prefs(context).getString(KEY_CALL_LOG_BACKUP_LABEL, "") ?: ""
        return saved.ifBlank { DEFAULT_CALL_LOG_BACKUP_LABEL }
    }

    fun saveCallLogBackupLabel(context: Context, label: String) {
        prefs(context).edit().putString(KEY_CALL_LOG_BACKUP_LABEL, label).apply()
    }

    // --- Manual per-SIM-slot info (Android can't reliably read your own number) ---

    fun simNumber(context: Context, slotIndex: Int): String =
        prefs(context).getString("sim_number_$slotIndex", "") ?: ""

    fun saveSimNumber(context: Context, slotIndex: Int, number: String) {
        prefs(context).edit().putString("sim_number_$slotIndex", number).apply()
    }

    fun simLabel(context: Context, slotIndex: Int): String =
        prefs(context).getString("sim_label_$slotIndex", "") ?: ""

    fun saveSimLabel(context: Context, slotIndex: Int, label: String) {
        prefs(context).edit().putString("sim_label_$slotIndex", label).apply()
    }

    // --- Templates ---

    fun smsTemplate(context: Context): MessageTemplate = loadTemplate(context, "sms_tpl_", MessageTemplate.defaultSms())
    fun callTemplate(context: Context): MessageTemplate = loadTemplate(context, "call_tpl_", MessageTemplate.defaultCall())

    fun saveSmsTemplate(context: Context, template: MessageTemplate) = saveTemplate(context, "sms_tpl_", template)
    fun saveCallTemplate(context: Context, template: MessageTemplate) = saveTemplate(context, "call_tpl_", template)

    fun resetSmsTemplate(context: Context) = saveSmsTemplate(context, MessageTemplate.defaultSms())
    fun resetCallTemplate(context: Context) = saveCallTemplate(context, MessageTemplate.defaultCall())

    private fun loadTemplate(context: Context, prefix: String, default: MessageTemplate): MessageTemplate {
        val p = prefs(context)
        return MessageTemplate(
            from = p.getString("${prefix}from", default.from) ?: default.from,
            fromName = p.getString("${prefix}fromname", default.fromName) ?: default.fromName,
            to = p.getString("${prefix}to", default.to) ?: default.to,
            cc = p.getString("${prefix}cc", default.cc) ?: default.cc,
            bcc = p.getString("${prefix}bcc", default.bcc) ?: default.bcc,
            subject = p.getString("${prefix}subject", default.subject) ?: default.subject,
            body = p.getString("${prefix}body", default.body) ?: default.body
        )
    }

    private fun saveTemplate(context: Context, prefix: String, template: MessageTemplate) {
        prefs(context).edit()
            .putString("${prefix}from", template.from)
            .putString("${prefix}fromname", template.fromName)
            .putString("${prefix}to", template.to)
            .putString("${prefix}cc", template.cc)
            .putString("${prefix}bcc", template.bcc)
            .putString("${prefix}subject", template.subject)
            .putString("${prefix}body", template.body)
            .apply()
    }
}
