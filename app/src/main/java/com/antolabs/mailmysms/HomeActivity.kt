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

        binding.settingsIconButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.aboutIconButton.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        binding.feedbackButton.setOnClickListener { AppInfo.showFeedbackDialog(this) }
        binding.coffeeButton.setOnClickListener { AppInfo.openUrl(this, AppInfo.COFFEE_URL) }
    }

    override fun onResume() {
        super.onResume()
        if (!SettingsStore.isConfigured(this)) return
        renderStats()
        renderRecentEvents()
        renderPendingWork()
        renderBackupStatus()
    }

    private sealed class BackupStatus {
        /** lastDeliveredMillis is null when forwarding is on and healthy but nothing has been
         *  confirmed delivered yet (e.g. right after setup) - that's still "active," just with
         *  nothing to timestamp. Never fabricate a time here. */
        data class Active(val lastDeliveredMillis: Long?) : BackupStatus()
        object Paused : BackupStatus()
        data class ActionNeeded(val reason: String) : BackupStatus()
        object Unknown : BackupStatus()
    }

    /** Derives current operational health from actual state (permissions, service liveness, the
     *  live-forwarding event history) rather than just echoing the on/off toggles - a toggle being
     *  on proves nothing about whether sending actually works right now. Deliberately does not
     *  treat a past successful delivery as a guarantee of future background execution; it only
     *  reports what's verifiable at this moment.
     *
     *  Known gap: a message currently mid-retry (WorkManager backoff, not yet exhausted) isn't
     *  visible here - EventLog only gets a row once a send finally succeeds or exhausts its
     *  retries, so a transient failure that's still retrying doesn't show as "action needed." That
     *  in-flight count is surfaced separately by renderPendingWork()/pendingStatText, which is the
     *  honest way to communicate "pending" per the brief, rather than this indicator guessing. */
    private fun computeBackupStatus(): BackupStatus = try {
        val smsOn = SettingsStore.smsEnabled(this)
        val callsOn = SettingsStore.callsEnabled(this)

        if (!smsOn && !callsOn) {
            BackupStatus.Paused
        } else if (smsOn && ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECEIVE_SMS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            BackupStatus.ActionNeeded("SMS permission required")
        } else if (callsOn && (
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CALL_LOG) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            )
        ) {
            BackupStatus.ActionNeeded("Call log permission required")
        } else if (callsOn && !CallMonitorService.isRunning()) {
            BackupStatus.ActionNeeded("Call monitoring isn't running — reopen Settings")
        } else {
            val log = EventLog.get(this)
            val mostRecent = log.recent(1).firstOrNull()
            if (mostRecent != null && !mostRecent.success) {
                BackupStatus.ActionNeeded(
                    if (mostRecent.authFailure) "Mailbox sign-in needs attention" else "Recent delivery failed — check Settings"
                )
            } else {
                BackupStatus.Active(log.mostRecentSuccess()?.timestamp)
            }
        }
    } catch (e: Exception) {
        BackupStatus.Unknown
    }

    private data class StatusDisplay(val icon: Int, val line1: String, val line2: String, val actionable: Boolean)

    private fun renderBackupStatus() {
        val status = computeBackupStatus()
        val now = System.currentTimeMillis()

        val display = when (status) {
            is BackupStatus.Active -> {
                val sub = status.lastDeliveredMillis?.let {
                    "Last delivered ${DateUtils.getRelativeTimeSpanString(it, now, DateUtils.MINUTE_IN_MILLIS)}"
                } ?: "Waiting for the first message"
                StatusDisplay(R.drawable.ic_status_check, "Backup active", sub, actionable = false)
            }
            BackupStatus.Paused -> StatusDisplay(
                R.drawable.ic_status_pause, "Backup paused", "Turn on SMS or call forwarding in Settings", actionable = true
            )
            is BackupStatus.ActionNeeded ->
                StatusDisplay(R.drawable.ic_status_alert, "Action needed", status.reason, actionable = true)
            BackupStatus.Unknown ->
                StatusDisplay(R.drawable.ic_status_unknown, "Checking status…", "Couldn't verify right now", actionable = false)
        }

        binding.backupStatusIcon.setImageResource(display.icon)
        binding.backupStatusLine1.text = display.line1
        binding.backupStatusLine2.text = display.line2
        binding.backupStatusContainer.isClickable = display.actionable
        binding.backupStatusContainer.background = if (display.actionable) {
            val outValue = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            ContextCompat.getDrawable(this, outValue.resourceId)
        } else null
        binding.backupStatusContainer.setOnClickListener(
            if (display.actionable) View.OnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) } else null
        )
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

    private fun renderStats() {
        val log = EventLog.get(this)

        // Live per-event forwarding, tracked since the app was installed (trimmed to the last
        // 60 days for the period breakdown further down, but the all-time counts here aren't).
        val allTimeTotal = log.countSince(0L)
        val allTimeSuccess = log.countSince(0L, successOnly = true)
        val allTimeFailed = allTimeTotal - allTimeSuccess
        val allTimeSms = log.countSince(0L, type = "SMS")
        val allTimeCalls = log.countSince(0L, type = "CALL")

        // Bulk one-time backup to email: a different mechanism (SmsBackupService/
        // CallLogBackupService) from live forwarding, but the same kind of outcome - the message
        // durably sits in the user's email. Folded into the same headline total for that reason;
        // failure tracking above stays scoped to live forwarding only, since the bulk tracker
        // only records successful imports.
        val user = SettingsStore.emailUser(this)
        val tracker = BackupTracker.get(this)
        val bulkMessages = tracker.importedCount(BackupTracker.TYPE_SMS, user) +
            tracker.importedCount(BackupTracker.TYPE_MMS, user) +
            tracker.importedCount(BackupTracker.TYPE_RCS, user)
        val bulkCalls = tracker.importedCount(BackupTracker.TYPE_CALL, user)

        val heroTotal = allTimeTotal + bulkMessages + bulkCalls
        val heroSms = allTimeSms + bulkMessages
        val heroCalls = allTimeCalls + bulkCalls
        val lastExport = SettingsStore.lastLocalExport(this)

        binding.headlineStatText.text = if (heroTotal == 0 && lastExport == null) {
            "Nothing forwarded yet — send yourself a text to see it in action."
        } else {
            buildHeroStatText(heroTotal, heroSms, heroCalls, lastExport)
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

    private fun buildHeroStatText(
        total: Int,
        sms: Int,
        callLogs: Int,
        lastExport: SettingsStore.LastLocalExport?
    ): SpannableStringBuilder {
        val accentColor = ContextCompat.getColor(this, R.color.brand_primary)
        val dimColor = 0x99FFFFFF.toInt()

        val smsPart = "$sms SMS"
        val callPart = "$callLogs call log${if (callLogs == 1) "" else "s"}"
        val breakdown = when {
            sms > 0 && callLogs > 0 -> "$smsPart · $callPart"
            sms > 0 -> smsPart
            callLogs > 0 -> callPart
            else -> null
        }

        val exportCount = (lastExport?.messageCount ?: 0) + (lastExport?.callCount ?: 0)

        return SpannableStringBuilder().apply {
            // Line 1: big number + value statement - only when there's something to show here;
            // a device with nothing live-forwarded or bulk-backed-up but a local export still
            // shouldn't claim "0 kept safe in your inbox".
            if (total > 0) {
                val numberText = total.grouped()
                append(numberText)
                setSpan(RelativeSizeSpan(1.6f), 0, numberText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(accentColor), 0, numberText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append(" kept safe in your inbox")
                // The view itself isn't bold (so lines 2/3 below read as clearly secondary) - only
                // this headline line is bolded explicitly.
                setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                // Line 2: breakdown, smaller and dimmer
                if (breakdown != null) {
                    append("\n")
                    val breakdownStart = length
                    append(breakdown)
                    setSpan(RelativeSizeSpan(0.8f), breakdownStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(ForegroundColorSpan(dimColor), breakdownStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }

            // A second, parallel headline - not a footnote on the one above. Deliberately not
            // folded into that number or phrasing (a local export is a point-in-time file on this
            // device, not durable off-device protection the way email is), but for many users this
            // is by far the bigger number, so it earns the same big-number/bold treatment, on its
            // own line with a blank line of separation, not a smaller "Plus ..." tacked on below.
            if (exportCount > 0) {
                if (total > 0) append("\n\n")
                val exportNumberText = exportCount.grouped()
                val exportLineStart = length
                append(exportNumberText)
                setSpan(RelativeSizeSpan(1.6f), exportLineStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(accentColor), exportLineStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append(" records saved as local backups")
                setSpan(StyleSpan(Typeface.BOLD), exportLineStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun renderRecentEvents() {
        binding.recentEventsContainer.removeAllViews()
        val events = EventLog.get(this).recent(3)

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
                textSize = 13f
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
