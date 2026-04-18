package com.bharatdroid.agent

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private var selectedAgentProvider = AIProvider.GEMINI
    private var selectedResearchProvider = AIProvider.GEMINI
    private var askPermission = true
    private var learningEnabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("bharatdroid", MODE_PRIVATE)

        val agentProviderStr = prefs.getString("agent_ai_provider", prefs.getString("ai_provider", "GEMINI")) ?: "GEMINI"
        val researchProviderStr = prefs.getString("research_ai_provider", agentProviderStr) ?: agentProviderStr
        selectedAgentProvider = parseProvider(agentProviderStr, AIProvider.GEMINI)
        selectedResearchProvider = parseProvider(researchProviderStr, selectedAgentProvider)
        askPermission = prefs.getBoolean("ask_permission", true)
        learningEnabled = getSharedPreferences("bharatdroid_memory", MODE_PRIVATE)
            .getBoolean("learning_enabled", true)

        val agentKeyField = findViewById<EditText>(R.id.etApiKey)
        val agentModelField = findViewById<EditText>(R.id.etModel)
        val researchKeyField = findViewById<EditText>(R.id.etResearchApiKey)
        val researchModelField = findViewById<EditText>(R.id.etResearchModel)

        agentKeyField.setText(prefs.getString("agent_ai_key", prefs.getString("ai_key", "")) ?: "")
        agentModelField.setText(prefs.getString("agent_ai_model", prefs.getString("ai_model", "")) ?: "")
        researchKeyField.setText(prefs.getString("research_ai_key", "") ?: "")
        researchModelField.setText(prefs.getString("research_ai_model", "") ?: "")

        setupProviderSection(
            initialProvider = selectedAgentProvider,
            geminiButtonId = R.id.btnProvGemini,
            claudeButtonId = R.id.btnProvClaude,
            openAiButtonId = R.id.btnProvOpenAI,
            keyHintId = R.id.tvKeyHint,
            keyFieldId = R.id.etApiKey,
        ) { provider ->
            selectedAgentProvider = provider
        }
        setupProviderSection(
            initialProvider = selectedResearchProvider,
            geminiButtonId = R.id.btnResearchProvGemini,
            claudeButtonId = R.id.btnResearchProvClaude,
            openAiButtonId = R.id.btnResearchProvOpenAI,
            keyHintId = R.id.tvResearchKeyHint,
            keyFieldId = R.id.etResearchApiKey,
        ) { provider ->
            selectedResearchProvider = provider
        }
        setupPermissionToggle()
        setupLearningToggle()

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val agentKey = agentKeyField.text.toString().trim()
            val researchKey = researchKeyField.text.toString().trim()
            val agentModel = agentModelField.text.toString().trim()
            val researchModel = researchModelField.text.toString().trim()

            if (agentKey.isBlank()) {
                Toast.makeText(this, "Agent API key cannot be empty.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit()
                .putString("agent_ai_key", agentKey)
                .putString("agent_ai_provider", selectedAgentProvider.name)
                .putString("agent_ai_model", agentModel)
                .putString("research_ai_key", researchKey)
                .putString("research_ai_provider", selectedResearchProvider.name)
                .putString("research_ai_model", researchModel)
                .putString("ai_key", agentKey)
                .putString("ai_provider", selectedAgentProvider.name)
                .putString("ai_model", agentModel)
                .putBoolean("ask_permission", askPermission)
                .apply()

            getSharedPreferences("bharatdroid_memory", MODE_PRIVATE)
                .edit().putBoolean("learning_enabled", learningEnabled).apply()

            stopService(Intent(this, AgentForegroundService::class.java))
            startForegroundService(Intent(this, AgentForegroundService::class.java))

            Toast.makeText(this, "Settings saved! Agent restarted.", Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<Button>(R.id.btnReset).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset BharatDroid?")
                .setMessage("This will erase all settings and take you back to setup. You will need to re-enter your bot token, agent AI key, and research AI key.")
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

    private fun setupProviderSection(
        initialProvider: AIProvider,
        geminiButtonId: Int,
        claudeButtonId: Int,
        openAiButtonId: Int,
        keyHintId: Int,
        keyFieldId: Int,
        onSelected: (AIProvider) -> Unit,
    ) {
        val btnGemini = findViewById<Button>(geminiButtonId)
        val btnClaude = findViewById<Button>(claudeButtonId)
        val btnOpenAI = findViewById<Button>(openAiButtonId)
        val keyHint = findViewById<TextView>(keyHintId)
        val keyField = findViewById<EditText>(keyFieldId)

        fun selectProvider(provider: AIProvider) {
            onSelected(provider)

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

        selectProvider(initialProvider)
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

    private fun setupLearningToggle() {
        val btnOn = findViewById<Button>(R.id.btnLearnOn)
        val btnOff = findViewById<Button>(R.id.btnLearnOff)

        fun selectLearning(enabled: Boolean) {
            learningEnabled = enabled
            if (enabled) {
                btnOn.setBackgroundColor(0xFF00CC88.toInt())
                btnOn.setTextColor(0xFF000000.toInt())
                btnOff.setBackgroundColor(0xFF1E1E1E.toInt())
                btnOff.setTextColor(0xFFAAAAAA.toInt())
            } else {
                btnOff.setBackgroundColor(0xFF5500AA.toInt())
                btnOff.setTextColor(0xFFFFFFFF.toInt())
                btnOn.setBackgroundColor(0xFF1E1E1E.toInt())
                btnOn.setTextColor(0xFFAAAAAA.toInt())
            }
        }

        btnOn.setOnClickListener { selectLearning(true) }
        btnOff.setOnClickListener { selectLearning(false) }

        selectLearning(learningEnabled)
    }

    private fun parseProvider(raw: String, fallback: AIProvider): AIProvider {
        return try {
            AIProvider.valueOf(raw)
        } catch (_: Exception) {
            fallback
        }
    }
}
