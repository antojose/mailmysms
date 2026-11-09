package com.antolabs.mailmysms

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.provider.Telephony
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.antolabs.mailmysms.databinding.ActivitySettingsBinding
import com.antolabs.mailmysms.databinding.DialogEmailAccountBinding
import kotlin.concurrent.thread

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    private val requiredPermissions: Array<String> by lazy {
        val base = mutableListOf(
            android.Manifest.permission.RECEIVE_SMS,
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.READ_CALL_LOG,
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            base.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        base.toTypedArray()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            Toast.makeText(this, "Denied: ${denied.joinToString()}", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
        }
        updatePermissionStatus()
        updateAvailableCounts()
        CallMonitorService.startIfNeeded(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = "Settings"

        binding.deviceLabelInput.setText(SettingsStore.deviceLabel(this))
        binding.smsSwitch.isChecked = SettingsStore.smsEnabled(this)
        binding.callsSwitch.isChecked = SettingsStore.callsEnabled(this)
        binding.sim1NumberInput.setText(SettingsStore.simNumber(this, 0))
        binding.sim1LabelInput.setText(SettingsStore.simLabel(this, 0))
        binding.sim2NumberInput.setText(SettingsStore.simNumber(this, 1))
        binding.sim2LabelInput.setText(SettingsStore.simLabel(this, 1))
        binding.smsBackupLabelInput.setText(SettingsStore.smsBackupLabel(this))
        binding.callLogBackupLabelInput.setText(SettingsStore.callLogBackupLabel(this))
        updateEmailButtonText()

        binding.emailAccountButton.setOnClickListener {
            showEmailAccountDialog()
        }

        binding.saveButton.setOnClickListener {
            SettingsStore.saveGeneral(
                this,
                binding.smsSwitch.isChecked,
                binding.callsSwitch.isChecked,
                binding.deviceLabelInput.text.toString().trim()
            )
            SettingsStore.saveSimNumber(this, 0, binding.sim1NumberInput.text.toString().trim())
            SettingsStore.saveSimLabel(this, 0, binding.sim1LabelInput.text.toString().trim())
            SettingsStore.saveSimNumber(this, 1, binding.sim2NumberInput.text.toString().trim())
            SettingsStore.saveSimLabel(this, 1, binding.sim2LabelInput.text.toString().trim())
            SettingsStore.saveSmsBackupLabel(this, binding.smsBackupLabelInput.text.toString().trim())
            SettingsStore.saveCallLogBackupLabel(this, binding.callLogBackupLabelInput.text.toString().trim())
            if (binding.callsSwitch.isChecked) {
                CallMonitorService.startIfNeeded(this)
            } else {
                CallMonitorService.stop(this)
            }
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        }

        binding.grantPermissionsButton.setOnClickListener {
            permissionLauncher.launch(requiredPermissions)
        }

        binding.batteryButton.setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }

        binding.testEmailButton.setOnClickListener {
            sendTestEmail()
        }

        binding.toggleAdvancedButton.setOnClickListener {
            val showing = binding.advancedSection.visibility == android.view.View.VISIBLE
            binding.advancedSection.visibility = if (showing) android.view.View.GONE else android.view.View.VISIBLE
            binding.toggleAdvancedButton.text = if (showing) "Show advanced options" else "Hide advanced options"
        }

        binding.editSmsTemplateButton.setOnClickListener {
            startActivity(Intent(this, TemplateActivity::class.java).putExtra(TemplateActivity.EXTRA_TYPE, TemplateActivity.TYPE_SMS))
        }

        binding.editCallTemplateButton.setOnClickListener {
            startActivity(Intent(this, TemplateActivity::class.java).putExtra(TemplateActivity.EXTRA_TYPE, TemplateActivity.TYPE_CALL))
        }

        binding.startSmsBackupButton.setOnClickListener {
            confirmAndStartSmsBackup()
        }

        binding.cancelSmsBackupButton.setOnClickListener {
            SmsBackupService.cancel()
            Toast.makeText(this, "Cancelling… will stop shortly", Toast.LENGTH_SHORT).show()
        }

        binding.startCallLogBackupButton.setOnClickListener {
            confirmAndStartCallLogBackup()
        }

        binding.cancelCallLogBackupButton.setOnClickListener {
            CallLogBackupService.cancel()
            Toast.makeText(this, "Cancelling… will stop shortly", Toast.LENGTH_SHORT).show()
        }

        binding.exportLocalButton.setOnClickListener {
            runLocalExport()
        }

        updatePermissionStatus()
    }

    override fun onResume() {
        super.onResume()
        startSmsBackupStatusPolling()
        updateAvailableCounts()
    }

    private fun updateAvailableCounts() {
        thread {
            val smsCount = if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
                val smsSelection = "${Telephony.Sms.TYPE} IN (${Telephony.Sms.MESSAGE_TYPE_INBOX}, ${Telephony.Sms.MESSAGE_TYPE_SENT})"
                val sms = contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf("_id"), smsSelection, null, null)?.use { it.count } ?: 0
                val mmsSelection = "${Telephony.Mms.MESSAGE_BOX} IN (${Telephony.Mms.MESSAGE_BOX_INBOX}, ${Telephony.Mms.MESSAGE_BOX_SENT})"
                val mms = contentResolver.query(Telephony.Mms.CONTENT_URI, arrayOf("_id"), mmsSelection, null, null)?.use { it.count } ?: 0
                val rcs = ImChatReader.queryAll(this).size
                sms + mms + rcs
            } else null

            val callCount = if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
                contentResolver.query(android.provider.CallLog.Calls.CONTENT_URI, arrayOf("_id"), null, null, null)?.use { it.count } ?: 0
            } else null

            runOnUiThread {
                binding.smsAvailableCountText.text = smsCount?.let { "${it.grouped()} messages available on this device (SMS + MMS + RCS)" }
                    ?: "Grant SMS permission to see available count"
                binding.callLogAvailableCountText.text = callCount?.let { "${it.grouped()} call log entries available on this device" }
                    ?: "Grant call log permission to see available count"
            }
        }
    }

    override fun onPause() {
        super.onPause()
        statusHandler.removeCallbacks(statusPoller)
    }

    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusPoller = object : Runnable {
        override fun run() {
            updateSmsBackupStatusText()
            updateCallLogBackupStatusText()
            statusHandler.postDelayed(this, 2000)
        }
    }

    private fun startSmsBackupStatusPolling() {
        statusHandler.removeCallbacks(statusPoller)
        statusHandler.post(statusPoller)
    }

    private fun updateSmsBackupStatusText() {
        val user = SettingsStore.emailUser(this)
        val tracker = BackupTracker.get(this)
        val count = tracker.importedCount(BackupTracker.TYPE_SMS, user) +
            tracker.importedCount(BackupTracker.TYPE_MMS, user) +
            tracker.importedCount(BackupTracker.TYPE_RCS, user)
        binding.smsBackupStatusText.text = if (SmsBackupService.isRunning()) {
            val (done, total) = SmsBackupService.progress()
            if (total > 0) "Backup running… ${done * 100 / total}% (${done.grouped()}/${total.grouped()})"
            else SmsBackupService.phase() ?: "Backup running…"
        } else if (count > 0) {
            "${count.grouped()} messages imported so far (safe to resume any time - already-sent ones won't be duplicated)"
        } else {
            ""
        }
    }

    private fun updateCallLogBackupStatusText() {
        val user = SettingsStore.emailUser(this)
        val count = BackupTracker.get(this).importedCount(BackupTracker.TYPE_CALL, user)
        binding.callLogBackupStatusText.text = if (CallLogBackupService.isRunning()) {
            val (done, total) = CallLogBackupService.progress()
            if (total > 0) "Backup running… ${done * 100 / total}% (${done.grouped()}/${total.grouped()})"
            else CallLogBackupService.phase() ?: "Backup running…"
        } else if (count > 0) {
            "${count.grouped()} calls imported so far (safe to resume any time - already-sent ones won't be duplicated)"
        } else {
            ""
        }
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun runLocalExport() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Grant SMS / call permissions first", Toast.LENGTH_SHORT).show()
            return
        }

        binding.exportLocalButton.isEnabled = false
        binding.exportLocalStatusText.text = "Exporting…"
        thread {
            var lastUiUpdate = 0L
            val result = try {
                LocalExporter.exportAll(this) { done, total ->
                    // The callback can fire thousands of times a second during the MMS read
                    // phase - throttle UI updates so it doesn't flood the main thread.
                    val now = System.currentTimeMillis()
                    if (now - lastUiUpdate >= 150) {
                        lastUiUpdate = now
                        val pct = (done * 100 / total)
                        runOnUiThread {
                            binding.exportLocalStatusText.text = "Exporting… $pct% (${done.grouped()}/${total.grouped()})"
                        }
                    }
                }
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                binding.exportLocalButton.isEnabled = true
                if (result == null) {
                    binding.exportLocalStatusText.text = "Export failed - see logs"
                } else {
                    val omittedNote = if (result.omittedCount > 0) " (${result.omittedCount.grouped()} omitted, because ${result.omittedReason})" else ""
                    binding.exportLocalStatusText.text =
                        "Exported ${result.messageCount.grouped()} messages + ${result.callCount.grouped()} calls to " +
                            "Download/MailMySMS-Exports (XML + CSV)$omittedNote"
                    val fileLines = result.files.joinToString("\n") { "• ${it.name}  (${formatFileSize(it.sizeBytes)})" }
                    AlertDialog.Builder(this)
                        .setTitle("Export complete")
                        .setMessage(
                            "${result.messageCount.grouped()} messages and ${result.callCount.grouped()} calls saved to " +
                                "Download/MailMySMS-Exports/:\n\n$fileLines\n\n" +
                                (if (result.omittedCount > 0) "${result.omittedCount.grouped()} omitted, because ${result.omittedReason}.\n\n" else "") +
                                "These files stay on this device only - nothing was sent by email."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun confirmAndStartSmsBackup() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Grant SMS / call permissions first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!SettingsStore.isConfigured(this)) {
            Toast.makeText(this, "Configure your email account first", Toast.LENGTH_SHORT).show()
            return
        }
        if (SmsBackupService.isRunning()) {
            Toast.makeText(this, "A backup is already running", Toast.LENGTH_SHORT).show()
            return
        }

        thread {
            val smsSelection = "${Telephony.Sms.TYPE} IN (${Telephony.Sms.MESSAGE_TYPE_INBOX}, ${Telephony.Sms.MESSAGE_TYPE_SENT})"
            val smsCount = contentResolver.query(
                Telephony.Sms.CONTENT_URI, arrayOf("_id"), smsSelection, null, null
            )?.use { it.count } ?: 0
            val mmsSelection = "${Telephony.Mms.MESSAGE_BOX} IN (${Telephony.Mms.MESSAGE_BOX_INBOX}, ${Telephony.Mms.MESSAGE_BOX_SENT})"
            val mmsCount = contentResolver.query(
                Telephony.Mms.CONTENT_URI, arrayOf("_id"), mmsSelection, null, null
            )?.use { it.count } ?: 0
            val rcsCount = ImChatReader.queryAll(this).size
            val count = smsCount + mmsCount + rcsCount
            val alreadyDone = BackupTracker.get(this).let {
                it.importedCount(BackupTracker.TYPE_SMS, SettingsStore.emailUser(this)) +
                    it.importedCount(BackupTracker.TYPE_MMS, SettingsStore.emailUser(this)) +
                    it.importedCount(BackupTracker.TYPE_RCS, SettingsStore.emailUser(this))
            }

            runOnUiThread {
                if (count == 0) {
                    Toast.makeText(this, "No SMS found to back up", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val remaining = (count - alreadyDone).coerceAtLeast(0)
                val estimateMinutes = (remaining * 0.35 / 60).toInt().coerceAtLeast(1)
                val resumeNote = if (alreadyDone > 0) " (${alreadyDone.grouped()} already backed up, won't be resent.)" else ""
                AlertDialog.Builder(this)
                    .setTitle(if (alreadyDone > 0) "Resume SMS backup" else "Backup existing SMS")
                    .setMessage(
                        "Found ${count.grouped()} messages (${smsCount.grouped()} SMS, ${mmsCount.grouped()} MMS, ${rcsCount.grouped()} RCS).$resumeNote This will run in the " +
                            "background as a notification and take roughly $estimateMinutes minutes, " +
                            "possibly longer. It's safe to cancel and resume later — already-sent " +
                            "messages won't be duplicated.\n\nContinue?"
                    )
                    .setPositiveButton("Start") { _, _ ->
                        SmsBackupService.start(this)
                        Toast.makeText(this, "Backup started", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun confirmAndStartCallLogBackup() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Grant SMS / call permissions first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!SettingsStore.isConfigured(this)) {
            Toast.makeText(this, "Configure your email account first", Toast.LENGTH_SHORT).show()
            return
        }
        if (CallLogBackupService.isRunning()) {
            Toast.makeText(this, "A backup is already running", Toast.LENGTH_SHORT).show()
            return
        }

        thread {
            val count = contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI, arrayOf("_id"), null, null, null
            )?.use { it.count } ?: 0
            val alreadyDone = BackupTracker.get(this).importedCount(BackupTracker.TYPE_CALL, SettingsStore.emailUser(this))

            runOnUiThread {
                if (count == 0) {
                    Toast.makeText(this, "No call log entries found to back up", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val remaining = (count - alreadyDone).coerceAtLeast(0)
                val estimateMinutes = (remaining * 0.35 / 60).toInt().coerceAtLeast(1)
                val resumeNote = if (alreadyDone > 0) " (${alreadyDone.grouped()} already backed up, won't be resent.)" else ""
                AlertDialog.Builder(this)
                    .setTitle(if (alreadyDone > 0) "Resume call log backup" else "Backup existing call log")
                    .setMessage(
                        "Found ${count.grouped()} call log entries.$resumeNote This will run in the " +
                            "background as a notification and take roughly $estimateMinutes minutes, " +
                            "possibly longer. It's safe to cancel and resume later — already-sent " +
                            "entries won't be duplicated.\n\nContinue?"
                    )
                    .setPositiveButton("Start") { _, _ ->
                        CallLogBackupService.start(this)
                        Toast.makeText(this, "Backup started", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun updateEmailButtonText() {
        val user = SettingsStore.emailUser(this)
        if (user.isBlank()) {
            binding.emailAccountButton.text = "Configure email account"
        } else {
            val provider = EmailProviders.matching(SettingsStore.imapHost(this)).label
            binding.emailAccountButton.text = "Email: $user ($provider) – tap to change"
        }
    }

    private fun showEmailAccountDialog() {
        val dialogBinding = DialogEmailAccountBinding.inflate(layoutInflater)
        dialogBinding.dialogEmailUserInput.setText(SettingsStore.emailUser(this))
        dialogBinding.dialogEmailPasswordInput.setText(SettingsStore.appPassword(this))

        val currentHost = SettingsStore.imapHost(this)
        val currentPort = SettingsStore.imapPort(this)
        dialogBinding.dialogImapHostInput.setText(currentHost)
        dialogBinding.dialogImapPortInput.setText(currentPort.toString())

        val initialPreset = EmailProviders.matching(currentHost)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, EmailProviders.all.map { it.label })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        dialogBinding.providerSpinner.adapter = adapter
        dialogBinding.providerSpinner.setSelection(EmailProviders.all.indexOf(initialPreset))
        dialogBinding.providerInstructions.text = initialPreset.instructions

        dialogBinding.providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val preset = EmailProviders.all[position]
                dialogBinding.providerInstructions.text = preset.instructions
                if (preset != EmailProviders.CUSTOM) {
                    dialogBinding.dialogImapHostInput.setText(preset.host)
                    dialogBinding.dialogImapPortInput.setText(preset.port.toString())
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        AlertDialog.Builder(this)
            .setTitle("Email account")
            .setView(dialogBinding.root)
            .setPositiveButton("Save") { _, _ ->
                val user = dialogBinding.dialogEmailUserInput.text.toString().trim()
                val password = dialogBinding.dialogEmailPasswordInput.text.toString().trim()
                val host = dialogBinding.dialogImapHostInput.text.toString().trim()
                val port = dialogBinding.dialogImapPortInput.text.toString().trim().toIntOrNull() ?: EmailProviders.GMAIL.port
                SettingsStore.saveAccount(this, user, password, host, port)
                updateEmailButtonText()
                Toast.makeText(this, "Email account saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updatePermissionStatus() {
        val allGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        binding.permissionStatus.text = if (allGranted) "Permissions: granted" else "Permissions: not granted"
    }

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, "Already exempt from battery optimization", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendTestEmail() {
        val user = SettingsStore.emailUser(this)
        val password = SettingsStore.appPassword(this)
        val host = SettingsStore.imapHost(this)
        val port = SettingsStore.imapPort(this)
        if (user.isBlank() || password.isBlank()) {
            Toast.makeText(this, "Configure your email account first", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Sending test email...", Toast.LENGTH_SHORT).show()
        thread {
            try {
                MailSender.append(
                    user, password, host, port,
                    PhoneUtils.emailAddress("test"), "Test",
                    user, "", "",
                    "Test email", "This is a test from MailMySMS."
                )
                runOnUiThread { Toast.makeText(this, "Test email sent", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }
}
