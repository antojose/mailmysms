package com.antolabs.mailmysms

import android.content.Context

object TemplateMailer {
    fun send(context: Context, template: MessageTemplate, tokens: Map<String, String>) {
        val emailUser = SettingsStore.emailUser(context)
        val appPassword = SettingsStore.appPassword(context)
        val imapHost = SettingsStore.imapHost(context)
        val imapPort = SettingsStore.imapPort(context)

        val from = TemplateEngine.render(template.from, tokens)
        val fromName = TemplateEngine.render(template.fromName, tokens)
        val to = TemplateEngine.render(template.to, tokens).ifBlank { emailUser }
        val cc = TemplateEngine.render(template.cc, tokens)
        val bcc = TemplateEngine.render(template.bcc, tokens)
        val subject = TemplateEngine.render(template.subject, tokens)
        val body = TemplateEngine.render(template.body, tokens)

        MailSender.append(emailUser, appPassword, imapHost, imapPort, from, fromName, to, cc, bcc, subject, body)
    }
}
