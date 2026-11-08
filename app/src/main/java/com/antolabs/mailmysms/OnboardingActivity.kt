package com.antolabs.mailmysms

import android.Manifest
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.antolabs.mailmysms.databinding.ActivityOnboardingBinding
import kotlin.concurrent.thread

class OnboardingActivity : AppCompatActivity() {
    private lateinit var binding: ActivityOnboardingBinding

    private val requiredPermissions: Array<String> by lazy {
        val base = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            base.add(Manifest.permission.POST_NOTIFICATIONS)
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupProviderSpinner()
        binding.deviceLabelInput.setText(Build.MODEL ?: "")
        binding.sim1NumberInput.setText(SettingsStore.simNumber(this, 0))
        binding.sim1LabelInput.setText(SettingsStore.simLabel(this, 0))
        binding.sim2NumberInput.setText(SettingsStore.simNumber(this, 1))
        binding.sim2LabelInput.setText(SettingsStore.simLabel(this, 1))
        binding.smsSwitch.isChecked = true
        binding.callsSwitch.isChecked = true

        binding.testEmailButton.setOnClickListener { sendTestEmail() }
        binding.grantPermissionsButton.setOnClickListener { permissionLauncher.launch(requiredPermissions) }
        binding.batteryButton.setOnClickListener { requestIgnoreBatteryOptimizations() }
        binding.finishButton.setOnClickListener { finishSetup() }

        renderPreview()
        updatePermissionStatus()
    }

    private fun setupProviderSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, EmailProviders.all.map { it.label })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.providerSpinner.adapter = adapter
        binding.providerSpinner.setSelection(0)
        binding.imapHostInput.setText(EmailProviders.GMAIL.host)
        binding.imapPortInput.setText(EmailProviders.GMAIL.port.toString())
        binding.providerInstructions.text = EmailProviders.GMAIL.instructions

        binding.providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val preset = EmailProviders.all[position]
                binding.providerInstructions.text = preset.instructions
                if (preset != EmailProviders.CUSTOM) {
                    binding.imapHostInput.setText(preset.host)
                    binding.imapPortInput.setText(preset.port.toString())
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun sendTestEmail() {
        val user = binding.emailUserInput.text.toString().trim()
        val password = binding.emailPasswordInput.text.toString().trim()
        val host = binding.imapHostInput.text.toString().trim()
        val port = binding.imapPortInput.text.toString().trim().toIntOrNull() ?: EmailProviders.GMAIL.port
        if (user.isBlank() || password.isBlank() || host.isBlank()) {
            Toast.makeText(this, "Fill in email, password, and host first", Toast.LENGTH_SHORT).show()
            return
        }
        binding.testStatusText.text = "Sending..."
        thread {
            try {
                MailSender.append(
                    user, password, host, port,
                    PhoneUtils.emailAddress("test"), "Test",
                    user, "", "",
                    "Test email", "This is a test from MailMySMS."
                )
                runOnUiThread { binding.testStatusText.text = "Success — check your inbox" }
            } catch (e: Exception) {
                runOnUiThread { binding.testStatusText.text = "Failed: ${e.message}" }
            }
        }
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

    private fun renderPreview() {
        val smsTokens = TokenCatalog.forType(TemplateActivity.TYPE_SMS).associate { it.name to it.example }
        val smsTemplate = SettingsStore.smsTemplate(this)
        binding.previewSmsText.text = "Subject: ${TemplateEngine.render(smsTemplate.subject, smsTokens)}\n\n" +
            TemplateEngine.render(smsTemplate.body, smsTokens)

        val callTokens = TokenCatalog.forType(TemplateActivity.TYPE_CALL).associate { it.name to it.example }
        val callTemplate = SettingsStore.callTemplate(this)
        binding.previewCallText.text = "Subject: ${TemplateEngine.render(callTemplate.subject, callTokens)}\n\n" +
            TemplateEngine.render(callTemplate.body, callTokens)
    }

    private fun finishSetup() {
        val user = binding.emailUserInput.text.toString().trim()
        val password = binding.emailPasswordInput.text.toString().trim()
        val host = binding.imapHostInput.text.toString().trim()
        val port = binding.imapPortInput.text.toString().trim().toIntOrNull() ?: EmailProviders.GMAIL.port
        if (user.isBlank() || password.isBlank()) {
            Toast.makeText(this, "Enter your email account details first", Toast.LENGTH_SHORT).show()
            return
        }
        SettingsStore.saveAccount(this, user, password, host, port)
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
        }

        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}
