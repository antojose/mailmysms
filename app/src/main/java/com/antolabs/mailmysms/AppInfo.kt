package com.antolabs.mailmysms

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog

object AppInfo {
    const val GITHUB_URL = "https://github.com/antojose/mailmysms"
    const val COFFEE_URL = "https://buymeacoffee.com/antojose"
    const val FEEDBACK_EMAIL = "antolabs.mailmysms@gmail.com"
    const val AUTHOR = "antojose"

    /**
     * Canonical privacy policy URL, for the Play Store listing and for an in-app link once the
     * page is live. As of this writing it returns a 502 (domain registered, nothing served yet),
     * so no screen links to this constant - wire it up once it resolves to real content.
     */
    const val PRIVACY_POLICY_URL = "https://mailmysms.app/privacy"

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

    fun showFeedbackDialog(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("How's MailMySMS working for you?")
            .setMessage(
                "Found a bug, have an idea, or just want to say hi? " +
                    "Genuine feedback — good or bad — genuinely helps."
            )
            .setPositiveButton("GitHub Issues") { _, _ -> openUrl(activity, "$GITHUB_URL/issues/new") }
            .setNegativeButton("Email") { _, _ -> sendFeedbackEmail(activity) }
            .setNeutralButton("Not now", null)
            .show()
    }
}
