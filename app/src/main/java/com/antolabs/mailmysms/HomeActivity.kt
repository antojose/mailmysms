package com.antolabs.mailmysms

import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.widget.LinearLayout
import android.widget.TextView
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.antolabs.mailmysms.databinding.ActivityHomeBinding
import java.util.Calendar
import kotlin.concurrent.thread

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!SettingsStore.isConfigured(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.headerMetaText.text = "by ${AppInfo.AUTHOR} · v${AppInfo.versionName(this)}"

        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.aboutButton.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        binding.feedbackButton.setOnClickListener { showFeedbackDialog() }
        binding.coffeeButton.setOnClickListener { AppInfo.openUrl(this, AppInfo.COFFEE_URL) }
    }

    override fun onResume() {
        super.onResume()
        if (!SettingsStore.isConfigured(this)) return
        renderStats()
        renderRecentEvents()
        renderPendingWork()
        renderBulkStats()
    }

    private fun renderBulkStats() {
        val user = SettingsStore.emailUser(this)
        val tracker = BackupTracker.get(this)
        val bulkMessages = tracker.importedCount(BackupTracker.TYPE_SMS, user) +
            tracker.importedCount(BackupTracker.TYPE_MMS, user) +
            tracker.importedCount(BackupTracker.TYPE_RCS, user)
        val bulkCalls = tracker.importedCount(BackupTracker.TYPE_CALL, user)
        val lastExport = SettingsStore.lastLocalExport(this)

        val lines = mutableListOf<String>()
        if (bulkMessages > 0 || bulkCalls > 0) {
            val parts = mutableListOf<String>()
            if (bulkMessages > 0) parts.add("$bulkMessages older message${if (bulkMessages == 1) "" else "s"}")
            if (bulkCalls > 0) parts.add("$bulkCalls older call${if (bulkCalls == 1) "" else "s"}")
            lines.add("Plus ${parts.joinToString(" and ")} backed up to email from your history")
        }
        if (lastExport != null) {
            val whenText = DateUtils.getRelativeTimeSpanString(
                lastExport.timeMillis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            )
            lines.add(
                "Last local export: ${lastExport.messageCount} messages + ${lastExport.callCount} calls " +
                    "saved to this device ($whenText)"
            )
        }

        if (lines.isEmpty()) {
            binding.bulkStatsText.visibility = View.GONE
        } else {
            binding.bulkStatsText.visibility = View.VISIBLE
            binding.bulkStatsText.text = lines.joinToString("\n")
        }
    }

    private fun renderPendingWork() {
        thread {
            val inFlightStates = setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)
            val wm = WorkManager.getInstance(this)
            val smsPending = try {
                wm.getWorkInfosByTag(EmailWorker::class.java.name).get().count { it.state in inFlightStates }
            } catch (e: Exception) { 0 }
            val callPending = try {
                wm.getWorkInfosByTag(CallLogWorker::class.java.name).get().count { it.state in inFlightStates }
            } catch (e: Exception) { 0 }
            val total = smsPending + callPending

            runOnUiThread {
                if (total > 0) {
                    binding.pendingStatText.visibility = View.VISIBLE
                    binding.pendingStatText.text = "$total waiting to be sent " +
                        "($smsPending SMS, $callPending call log${if (callPending == 1) "" else "s"})"
                } else {
                    binding.pendingStatText.visibility = View.GONE
                }
            }
        }
    }

    private fun showFeedbackDialog() {
        AlertDialog.Builder(this)
            .setTitle("How's MailMySMS working for you?")
            .setMessage(
                "Found a bug, have an idea, or just want to say hi? " +
                    "Genuine feedback — good or bad — genuinely helps."
            )
            .setPositiveButton("GitHub Issues") { _, _ -> AppInfo.openUrl(this, "${AppInfo.GITHUB_URL}/issues/new") }
            .setNegativeButton("Email") { _, _ -> AppInfo.sendFeedbackEmail(this) }
            .setNeutralButton("Not now", null)
            .show()
    }

    private fun renderStats() {
        val log = EventLog.get(this)

        val allTimeTotal = log.countSince(0L)
        val allTimeSuccess = log.countSince(0L, successOnly = true)
        val allTimeFailed = allTimeTotal - allTimeSuccess
        val allTimeSms = log.countSince(0L, type = "SMS")
        val allTimeCalls = log.countSince(0L, type = "CALL")

        binding.headlineStatText.text = if (allTimeTotal == 0) {
            "Nothing forwarded yet — send yourself a text to see it in action."
        } else {
            buildHeroStatText(allTimeTotal, allTimeSms, allTimeCalls)
        }

        if (allTimeFailed > 0) {
            binding.failedStatText.visibility = View.VISIBLE
            binding.failedStatText.text = "$allTimeFailed couldn't be delivered — check Settings"
        } else {
            binding.failedStatText.visibility = View.GONE
        }

        val now = System.currentTimeMillis()
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val weekStart = now - 7L * 24 * 60 * 60 * 1000
        val monthStart = now - 30L * 24 * 60 * 60 * 1000

        fun line(label: String, since: Long): String = "$label: ${log.countSince(since)} sent"

        binding.periodStatsText.text = listOf(
            line("Today", todayStart),
            line("Last 7 days", weekStart),
            line("Last 30 days", monthStart)
        ).joinToString("   ·   ")
    }

    private fun buildHeroStatText(total: Int, sms: Int, callLogs: Int): SpannableStringBuilder {
        val numberText = total.toString()
        val accentColor = ContextCompat.getColor(this, R.color.brand_primary)
        val dimColor = 0x99FFFFFF.toInt()

        val smsPart = "$sms SMS"
        val callPart = "$callLogs call log${if (callLogs == 1) "" else "s"}"
        val breakdown = when {
            sms > 0 && callLogs > 0 -> "$smsPart · $callPart"
            sms > 0 -> smsPart
            else -> callPart
        }

        return SpannableStringBuilder().apply {
            // Line 1: big number + value statement
            append(numberText)
            setSpan(RelativeSizeSpan(1.6f), 0, numberText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(accentColor), 0, numberText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(" kept safe in your inbox")
            append("\n")

            // Line 2: breakdown, smaller and dimmer
            val breakdownStart = length
            append(breakdown)
            setSpan(RelativeSizeSpan(0.8f), breakdownStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(dimColor), breakdownStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun renderRecentEvents() {
        binding.recentEventsContainer.removeAllViews()
        val events = EventLog.get(this).recent(5)

        if (events.isEmpty()) {
            binding.recentEventsContainer.addView(
                TextView(this).apply {
                    text = "No events yet"
                    alpha = 0.6f
                    setPadding(0, 16, 0, 16)
                }
            )
            return
        }

        val successColor = ContextCompat.getColor(this, R.color.status_success)
        val failedColor = ContextCompat.getColor(this, R.color.status_failed)

        events.forEach { event ->
            val row = TextView(this).apply {
                val statusWord = if (event.success) "Delivered" else "Failed"
                val statusColor = if (event.success) successColor else failedColor
                val time = DateUtils.getRelativeTimeSpanString(
                    event.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
                )

                text = SpannableStringBuilder().apply {
                    val statusLabel = "● $statusWord  "
                    append(statusLabel)
                    setSpan(ForegroundColorSpan(statusColor), 0, statusLabel.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(StyleSpan(Typeface.BOLD), 0, statusLabel.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    append(event.summary)
                    append("\n")
                    val timeStart = length
                    append(time)
                    setSpan(RelativeSizeSpan(0.85f), timeStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(android.text.style.ForegroundColorSpan(0x99FFFFFF.toInt()), timeStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                setPadding(0, 20, 0, 20)
                textSize = 14f
            }
            binding.recentEventsContainer.addView(row)
            binding.recentEventsContainer.addView(
                View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(0x1FFFFFFF)
                }
            )
        }
    }
}
