package com.antolabs.mailmysms

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.antolabs.mailmysms.databinding.ActivityTemplateBinding

class TemplateActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTemplateBinding
    private lateinit var type: String
    private var activeField: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTemplateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        type = intent.getStringExtra(EXTRA_TYPE) ?: TYPE_SMS
        title = if (type == TYPE_SMS) "SMS email template" else "Call email template"

        val fields = listOf(
            binding.fromInput, binding.fromNameInput, binding.toInput,
            binding.ccInput, binding.bccInput, binding.subjectInput, binding.bodyInput
        )
        fields.forEach { field ->
            field.setOnFocusChangeListener { v, hasFocus -> if (hasFocus) activeField = v as EditText }
        }
        activeField = binding.bodyInput

        loadTemplate()

        binding.tokenReferenceButton.setOnClickListener { showTokenReferenceDialog() }
        binding.insertTokenFab.setOnClickListener { showTokenPicker() }

        binding.saveTemplateButton.setOnClickListener {
            saveTemplate()
            Toast.makeText(this, "Template saved", Toast.LENGTH_SHORT).show()
        }

        binding.resetTemplateButton.setOnClickListener {
            if (type == TYPE_SMS) SettingsStore.resetSmsTemplate(this) else SettingsStore.resetCallTemplate(this)
            loadTemplate()
            Toast.makeText(this, "Reset to default", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadTemplate() {
        val t = if (type == TYPE_SMS) SettingsStore.smsTemplate(this) else SettingsStore.callTemplate(this)
        binding.fromInput.setText(t.from)
        binding.fromNameInput.setText(t.fromName)
        binding.toInput.setText(t.to)
        binding.ccInput.setText(t.cc)
        binding.bccInput.setText(t.bcc)
        binding.subjectInput.setText(t.subject)
        binding.bodyInput.setText(t.body)
    }

    private fun saveTemplate() {
        val t = MessageTemplate(
            from = binding.fromInput.text.toString(),
            fromName = binding.fromNameInput.text.toString(),
            to = binding.toInput.text.toString(),
            cc = binding.ccInput.text.toString(),
            bcc = binding.bccInput.text.toString(),
            subject = binding.subjectInput.text.toString(),
            body = binding.bodyInput.text.toString()
        )
        if (type == TYPE_SMS) SettingsStore.saveSmsTemplate(this, t) else SettingsStore.saveCallTemplate(this, t)
    }

    private fun insertToken(tokenName: String) {
        val field = activeField
        if (field == null) {
            Toast.makeText(this, "Tap into a field first", Toast.LENGTH_SHORT).show()
            return
        }
        val editable = field.text ?: return
        val start = field.selectionStart.coerceIn(0, editable.length)
        val end = field.selectionEnd.coerceIn(0, editable.length)
        val insertText = "{$tokenName}"
        val from = minOf(start, end)
        val to = maxOf(start, end)
        editable.replace(from, to, insertText)
        field.setSelection(from + insertText.length)
    }

    private fun showTokenReferenceDialog() {
        val tokens = TokenCatalog.forType(type)
        val sb = StringBuilder()
        sb.append(TokenCatalog.missingSenderNetworkNote).append("\n\n")
        tokens.forEach { t ->
            sb.append("{${t.name}}\n")
            sb.append("  ${t.description}\n")
            sb.append("  e.g. ${t.example}\n\n")
        }
        val textView = TextView(this).apply {
            text = sb.toString()
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setPadding(48, 24, 48, 24)
        }
        val scroll = ScrollView(this).apply { addView(textView) }
        AlertDialog.Builder(this)
            .setTitle("Token reference")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showTokenPicker() {
        val tokens = TokenCatalog.forType(type)
        val outValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)

        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val header = TextView(this).apply {
            text = "Tap a token to insert it at the cursor"
            setPadding(32, 24, 32, 16)
            textSize = 13f
            alpha = 0.7f
        }
        container.addView(header)

        tokens.forEach { t ->
            val row = TextView(this).apply {
                val builder = android.text.SpannableStringBuilder()
                builder.append("{${t.name}}")
                builder.setSpan(
                    android.text.style.StyleSpan(Typeface.BOLD),
                    0, builder.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                builder.append("\n").append(t.description)
                text = builder
                textSize = 14f
                setPadding(32, 24, 32, 24)
                isClickable = true
                isFocusable = true
                if (outValue.resourceId != 0) setBackgroundResource(outValue.resourceId)
                setOnClickListener { insertToken(t.name) }
            }
            container.addView(row)
            container.addView(
                View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(0x1FFFFFFF)
                }
            )
        }

        val scroll = ScrollView(this).apply { addView(container) }
        BottomSheetDialog(this).apply {
            setContentView(scroll)
            show()
        }
    }

    companion object {
        const val EXTRA_TYPE = "type"
        const val TYPE_SMS = "sms"
        const val TYPE_CALL = "call"
    }
}
