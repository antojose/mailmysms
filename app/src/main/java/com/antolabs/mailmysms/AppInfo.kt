package com.antolabs.mailmysms

import android.content.Context
import android.content.Intent
import android.net.Uri

object AppInfo {
    const val GITHUB_URL = "https://github.com/antojose/mailmysms"
    const val COFFEE_URL = "https://buymeacoffee.com/antojose"
    const val FEEDBACK_EMAIL = "antolabs.mailmysms@gmail.com"
    const val AUTHOR = "antojose"

    fun versionName(context: Context): String =
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }

    fun openUrl(context: Context, url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    fun sendFeedbackEmail(context: Context) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, "MailMySMS feedback")
        }
        context.startActivity(Intent.createChooser(intent, "Send feedback"))
    }
}
