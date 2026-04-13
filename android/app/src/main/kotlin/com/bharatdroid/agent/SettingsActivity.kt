package com.bharatdroid.agent

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private var selectedProvider = AIProvider.GEMINI
    private var askPermission = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("bharatdroid", MODE_PRIVATE)

        // Load current settings
        val providerStr = prefs.getString("ai_provider", "GEMINI") ?: "GEMINI"
        selectedProvider = try { AIProvider.valueOf(providerStr) } catch (_: Exception) { AIProvider.GEMINI }
        askPermission = prefs.getBoolean("ask_permission", true)
        val currentKey = prefs.getString("ai_key", "") ?: ""
        val currentModel = prefs.getString("ai_model", "") ?: ""

        val keyField = findViewById<EditText>(R.id.etApiKey)
        val modelField = findViewById<EditText>(R.id.etModel)
        keyField.setText(currentKey)
        modelField.setText(currentModel)

        setupProviderButtons()
        setupPermissionToggle()

        // Back
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        // Save
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val key = keyField.text.toString().trim()
            if (key.isBlank()) {
                Toast.makeText(this, "API key cannot be empty.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit()
                .putString("ai_key", key)
                .putString("ai_provider", selectedProvider.name)
                .putString("ai_model", modelField.text.toString().trim())
                .putBoolean("ask_permission", askPermission)
                .apply()

            // Restart agent service
            stopService(Intent(this, AgentForegroundService::class.java))
            startForegroundService(Intent(this, AgentForegroundService::class.java))

            Toast.makeText(this, "Settings saved! Agent restarted.", Toast.LENGTH_SHORT).show()
            finish()
        }

        // Reset App
        findViewById<Button>(R.id.btnReset).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset BharatDroid?")
                .setMessage("This will erase all settings and take you back to setup. You will need to re-enter your bot token and API key.")
                .setPositiveButton("Reset") { _, _ ->
                    stopService(Intent(this, AgentForegroundService::class.java))
                    prefs.edit().clear().apply()
                    startActivity(Intent(this, OnboardingActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                    finishAffinity()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun setupProviderButtons() {
        val btnGemini = findViewById<Button>(R.id.btnProvGemini)
        val btnClaude = findViewById<Button>(R.id.btnProvClaude)
        val btnOpenAI = findViewById<Button>(R.id.btnProvOpenAI)
        val keyHint = findViewById<TextView>(R.id.tvKeyHint)
        val keyField = findViewById<EditText>(R.id.etApiKey)

        fun selectProvider(provider: AIProvider) {
            selectedProvider = provider

            // Reset all buttons
            listOf(btnGemini, btnClaude, btnOpenAI).forEach { btn ->
                btn.setBackgroundColor(0xFF1E1E1E.toInt())
                btn.setTextColor(0xFFAAAAAA.toInt())
            }

            when (provider) {
                AIProvider.GEMINI -> {
                    btnGemini.setBackgroundColor(0xFF1A73E8.toInt())
                    btnGemini.setTextColor(0xFFFFFFFF.toInt())
                    keyHint.text = "Get your free key at aistudio.google.com/apikey"
                    keyField.hint = "AIzaSy..."
                }
                AIProvider.CLAUDE -> {
                    btnClaude.setBackgroundColor(0xFFCC7000.toInt())
                    btnClaude.setTextColor(0xFFFFFFFF.toInt())
                    keyHint.text = "Get key at console.anthropic.com (paid)"
                    keyField.hint = "sk-ant-api03-..."
                }
                AIProvider.OPENAI -> {
                    btnOpenAI.setBackgroundColor(0xFF10A37F.toInt())
                    btnOpenAI.setTextColor(0xFFFFFFFF.toInt())
                    keyHint.text = "Get key at platform.openai.com/api-keys (paid)"
                    keyField.hint = "sk-proj-..."
                }
            }
        }

        btnGemini.setOnClickListener { selectProvider(AIProvider.GEMINI) }
        btnClaude.setOnClickListener { selectProvider(AIProvider.CLAUDE) }
        btnOpenAI.setOnClickListener { selectProvider(AIProvider.OPENAI) }

        // Set initial state
        selectProvider(selectedProvider)
    }

    private fun setupPermissionToggle() {
        val btnAsk = findViewById<Button>(R.id.btnAskPerm)
        val btnJust = findViewById<Button>(R.id.btnJustDoIt)

        fun selectMode(ask: Boolean) {
            askPermission = ask
            if (ask) {
                btnAsk.setBackgroundColor(0xFF00CC88.toInt())
                btnAsk.setTextColor(0xFF000000.toInt())
                btnJust.setBackgroundColor(0xFF1E1E1E.toInt())
                btnJust.setTextColor(0xFFAAAAAA.toInt())
            } else {
                btnJust.setBackgroundColor(0xFFFF4500.toInt())
                btnJust.setTextColor(0xFFFFFFFF.toInt())
                btnAsk.setBackgroundColor(0xFF1E1E1E.toInt())
                btnAsk.setTextColor(0xFFAAAAAA.toInt())
            }
        }

        btnAsk.setOnClickListener { selectMode(true) }
        btnJust.setOnClickListener { selectMode(false) }

        selectMode(askPermission)
    }
}
