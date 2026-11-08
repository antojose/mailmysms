package com.antolabs.mailmysms

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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

        updatePermissionStatus()
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
