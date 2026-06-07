package com.bharatdroid.agent

import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telecom.TelecomManager
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
    private var ttsEnabled = false
    private var ttsVoice = "alloy"
    private var notchEnabled = true
    private var ultraMode = false
    private var callAnsweringEnabled = false

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
        ttsEnabled = prefs.getBoolean("tts_enabled", false)
        ttsVoice = prefs.getString("tts_voice", "alloy") ?: "alloy"
        notchEnabled = prefs.getBoolean("notch_overlay_enabled", true)
        ultraMode = prefs.getBoolean("ultra_mode", false)
        callAnsweringEnabled = prefs.getBoolean("call_answering_enabled", false)

        val agentKeyField = findViewById<EditText>(R.id.etApiKey)
        val agentModelField = findViewById<EditText>(R.id.etModel)
        val researchKeyField = findViewById<EditText>(R.id.etResearchApiKey)
        val researchModelField = findViewById<EditText>(R.id.etResearchModel)

        agentKeyField.setText(prefs.getString("agent_ai_key", prefs.getString("ai_key", "")) ?: "")
        agentModelField.setText(prefs.getString("agent_ai_model", prefs.getString("ai_model", "")) ?: "")
        researchKeyField.setText(prefs.getString("research_ai_key", "") ?: "")
        researchModelField.setText(prefs.getString("research_ai_model", "") ?: "")

        val ttsKeyField = findViewById<EditText>(R.id.etTtsKey)
        ttsKeyField.setText(prefs.getString("tts_api_key", "") ?: "")

        val imageApiKeyField = findViewById<EditText?>(R.id.etImageApiKey)
        imageApiKeyField?.setText(prefs.getString("image_api_key", "") ?: "")

        val waNumberField = findViewById<EditText?>(R.id.etWhatsappNumber)
        waNumberField?.setText(prefs.getString("whatsapp_channel_number", "") ?: "")

        val elevenLabsKeyField = findViewById<EditText?>(R.id.etElevenLabsKey)
        elevenLabsKeyField?.setText(prefs.getString("elevenlabs_api_key", "") ?: "")

        val elevenLabsVoiceIdField = findViewById<EditText?>(R.id.etElevenLabsVoiceId)
        elevenLabsVoiceIdField?.setText(prefs.getString("elevenlabs_voice_id", "") ?: "")

        val ownerNameField = findViewById<EditText?>(R.id.etOwnerName)
        ownerNameField?.setText(prefs.getString("owner_name", "") ?: "")

        val vipNumbersField = findViewById<EditText?>(R.id.etVipNumbers)
        vipNumbersField?.setText(prefs.getString("vip_caller_numbers", "") ?: "")

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
        setupTtsToggle()
        setupVoicePicker()
        setupNotchToggle()
        setupModeToggle()
        setupCallAnswering()

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

            val ttsKey = ttsKeyField.text.toString().trim()
            val imageApiKey = imageApiKeyField?.text?.toString()?.trim().orEmpty()
            val elevenLabsKey = elevenLabsKeyField?.text?.toString()?.trim().orEmpty()
            val elevenLabsVoiceId = elevenLabsVoiceIdField?.text?.toString()?.trim().orEmpty()
            val ownerName = ownerNameField?.text?.toString()?.trim().orEmpty()
            val vipNumbers = vipNumbersField?.text?.toString()?.trim().orEmpty()
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
                .putString("tts_api_key", ttsKey)
                .putBoolean("tts_enabled", ttsEnabled)
                .putString("tts_voice", ttsVoice)
                .putString("image_api_key", imageApiKey)
                .putBoolean("notch_overlay_enabled", notchEnabled)
                .putBoolean("ultra_mode", ultraMode)
                .putString("whatsapp_channel_number", waNumberField?.text?.toString()?.trim().orEmpty())
                .putBoolean("call_answering_enabled", callAnsweringEnabled)
                .putString("elevenlabs_api_key", elevenLabsKey)
                .putString("elevenlabs_voice_id", elevenLabsVoiceId)
                .putString("owner_name", ownerName)
                .putString("vip_caller_numbers", vipNumbers)
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
                .setTitle("Reset BharatClaw?")
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

    private fun setupTtsToggle() {
        val btnOn = findViewById<Button>(R.id.btnTtsOn)
        val btnOff = findViewById<Button>(R.id.btnTtsOff)

        fun render(enabled: Boolean) {
            ttsEnabled = enabled
            if (enabled) {
                btnOn.setBackgroundColor(0xFF00CC88.toInt())
                btnOn.setTextColor(0xFF000000.toInt())
                btnOff.setBackgroundColor(0xFF1A1A1A.toInt())
                btnOff.setTextColor(0xFFAAAAAA.toInt())
            } else {
                btnOff.setBackgroundColor(0xFF555555.toInt())
                btnOff.setTextColor(0xFFFFFFFF.toInt())
                btnOn.setBackgroundColor(0xFF1A1A1A.toInt())
                btnOn.setTextColor(0xFFAAAAAA.toInt())
            }
        }

        btnOn.setOnClickListener { render(true) }
        btnOff.setOnClickListener { render(false) }
        render(ttsEnabled)
    }

    private fun setupVoicePicker() {
        val btnAlloy = findViewById<Button>(R.id.btnVoiceAlloy)
        val btnNova = findViewById<Button>(R.id.btnVoiceNova)
        val btnShimmer = findViewById<Button>(R.id.btnVoiceShimmer)
        val all = listOf(btnAlloy, btnNova, btnShimmer)

        fun render(voice: String) {
            ttsVoice = voice
            all.forEach {
                it.setBackgroundColor(0xFF1A1A1A.toInt())
                it.setTextColor(0xFFAAAAAA.toInt())
            }
            val active = when (voice) {
                "nova" -> btnNova
                "shimmer" -> btnShimmer
                else -> btnAlloy
            }
            active.setBackgroundColor(0xFFFF5C00.toInt())
            active.setTextColor(0xFFFFFFFF.toInt())
        }

        btnAlloy.setOnClickListener { render("alloy") }
        btnNova.setOnClickListener { render("nova") }
        btnShimmer.setOnClickListener { render("shimmer") }
        render(ttsVoice)
    }

    private fun setupNotchToggle() {
        val btnOn = findViewById<Button>(R.id.btnNotchOn)
        val btnOff = findViewById<Button>(R.id.btnNotchOff)
        val btnGrant = findViewById<Button>(R.id.btnGrantOverlay)

        fun render(enabled: Boolean) {
            notchEnabled = enabled
            if (enabled) {
                btnOn.setBackgroundColor(0xFF00CC88.toInt())
                btnOn.setTextColor(0xFF000000.toInt())
                btnOff.setBackgroundColor(0xFF1A1A1A.toInt())
                btnOff.setTextColor(0xFFAAAAAA.toInt())
            } else {
                btnOff.setBackgroundColor(0xFF555555.toInt())
                btnOff.setTextColor(0xFFFFFFFF.toInt())
                btnOn.setBackgroundColor(0xFF1A1A1A.toInt())
                btnOn.setTextColor(0xFFAAAAAA.toInt())
            }
        }

        btnOn.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Grant 'Display over other apps' first.", Toast.LENGTH_SHORT).show()
            } else {
                render(true)
            }
        }
        btnOff.setOnClickListener { render(false) }
        btnGrant.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }

        // Hide grant button if permission already granted
        btnGrant.visibility = if (Settings.canDrawOverlays(this))
            android.view.View.GONE else android.view.View.VISIBLE

        render(notchEnabled)
    }

    private fun setupModeToggle() {
        val btnEfficient = findViewById<Button>(R.id.btnModeEfficient)
        val btnUltra = findViewById<Button>(R.id.btnModeUltra)

        fun render(ultra: Boolean) {
            ultraMode = ultra
            if (!ultra) {
                btnEfficient.setBackgroundColor(0xFF00CC88.toInt())
                btnEfficient.setTextColor(0xFF000000.toInt())
                btnUltra.setBackgroundColor(0xFF1A1A1A.toInt())
                btnUltra.setTextColor(0xFFAAAAAA.toInt())
            } else {
                btnUltra.setBackgroundColor(0xFFFF5C00.toInt())
                btnUltra.setTextColor(0xFFFFFFFF.toInt())
                btnEfficient.setBackgroundColor(0xFF1A1A1A.toInt())
                btnEfficient.setTextColor(0xFFAAAAAA.toInt())
            }
        }

        btnEfficient.setOnClickListener { render(false) }
        btnUltra.setOnClickListener { render(true) }
        render(ultraMode)
    }

    private fun setupCallAnswering() {
        val btnOn = findViewById<Button>(R.id.btnCallAnswerOn)
        val btnOff = findViewById<Button>(R.id.btnCallAnswerOff)
        val btnDialer = findViewById<Button>(R.id.btnSetDefaultDialer)

        fun render(enabled: Boolean) {
            callAnsweringEnabled = enabled
            if (enabled) {
                btnOn.setBackgroundColor(0xFF00CC88.toInt())
                btnOn.setTextColor(0xFF000000.toInt())
                btnOff.setBackgroundColor(0xFF1A1A1A.toInt())
                btnOff.setTextColor(0xFFAAAAAA.toInt())
            } else {
                btnOff.setBackgroundColor(0xFF555555.toInt())
                btnOff.setTextColor(0xFFFFFFFF.toInt())
                btnOn.setBackgroundColor(0xFF1A1A1A.toInt())
                btnOn.setTextColor(0xFFAAAAAA.toInt())
            }
        }

        btnOn.setOnClickListener { render(true) }
        btnOff.setOnClickListener { render(false) }
        render(callAnsweringEnabled)

        btnDialer.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val rm = getSystemService(RoleManager::class.java)
                if (!rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER), 99)
                } else {
                    Toast.makeText(this, "BharatClaw is already the default Phone app.", Toast.LENGTH_SHORT).show()
                }
            } else {
                val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                    .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
                startActivity(intent)
            }
        }
    }

    private fun parseProvider(raw: String, fallback: AIProvider): AIProvider {
        return try {
            AIProvider.valueOf(raw)
        } catch (_: Exception) {
            fallback
        }
    }
}
