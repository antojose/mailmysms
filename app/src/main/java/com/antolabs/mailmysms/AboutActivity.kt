package com.antolabs.mailmysms

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.antolabs.mailmysms.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = "About"

        binding.versionText.text = "by ${AppInfo.AUTHOR} · v${AppInfo.versionName(this)}"

        binding.githubButton.setOnClickListener { AppInfo.openUrl(this, AppInfo.GITHUB_URL) }
        binding.coffeeButton.setOnClickListener { AppInfo.openUrl(this, AppInfo.COFFEE_URL) }
        binding.feedbackButton.setOnClickListener { AppInfo.openUrl(this, "${AppInfo.GITHUB_URL}/issues/new") }
        binding.feedbackEmailButton.setOnClickListener { AppInfo.sendFeedbackEmail(this) }
    }
}
