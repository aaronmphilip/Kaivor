package com.bharatdroid.agent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*

class OnboardingActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var botToken = ""
    private var chatId = -1L
    private var botUsername = ""  // The actual @username, not display name
    private var botDisplayName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If already configured, skip to dashboard
        val prefs = getSharedPreferences("bharatdroid", MODE_PRIVATE)
        if (prefs.getString("bot_token", null) != null && prefs.getLong("chat_id", -1L) != -1L) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_onboarding)

        setupStep1()
        setupStep2()
        setupStep3()
        setupStep4()
        setupStep5()
    }

    override fun onResume() {
        super.onResume()
        // Accessibility status
        findViewById<TextView>(R.id.tvAccessStatus)?.let { tv ->
            if (AgentAccessibilityService.isConnected) {
                tv.text = "✓ Accessibility enabled"
                tv.setTextColor(0xFF00CC88.toInt())
            } else {
                tv.text = "✗ Not enabled yet"
                tv.setTextColor(0xFFFF6B6B.toInt())
            }
        }
        // Notification access status
        findViewById<TextView>(R.id.tvNotifStatus)?.let { tv ->
            if (NotificationRelay.isPermissionGranted(this)) {
                tv.text = "✓ Notification access enabled — relay is active"
                tv.setTextColor(0xFF00CC88.toInt())
            } else {
                tv.text = "✗ Not enabled (optional — tap button below)"
                tv.setTextColor(0xFFAAAAAA.toInt())
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ── Step 1: Welcome ──────────────────────

    private fun setupStep1() {
        findViewById<Button>(R.id.btnGetStarted).setOnClickListener {
            showStep(2)
        }
    }

    // ── Step 2: Telegram Bot Token ───────────

    private fun setupStep2() {
        findViewById<Button>(R.id.btnOpenBotFather).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=BotFather")))
            } catch (_: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/BotFather")))
            }
        }

        findViewById<Button>(R.id.btnVerifyToken).setOnClickListener {
            val token = findViewById<EditText>(R.id.etBotToken).text.toString().trim()
            if (token.isBlank()) {
                showTokenStatus("Paste your bot token first.", false)
                return@setOnClickListener
            }
            botToken = token
            verifyToken()
        }
    }

    private fun verifyToken() {
        val status = findViewById<TextView>(R.id.tvTokenStatus)
        val btn = findViewById<Button>(R.id.btnVerifyToken)
        btn.isEnabled = false
        status.text = "Checking..."
        status.setTextColor(0xFFAAAA00.toInt())

        scope.launch {
            val detector = ChatIdDetector(botToken)
            val botInfo = detector.getBotInfo()
            if (botInfo != null) {
                botDisplayName = botInfo.displayName
                botUsername = botInfo.username  // The actual @username
                showTokenStatus("Bot verified: ${botInfo.displayName} (@${botInfo.username})", true)
                btn.isEnabled = true
                delay(800)
                showStep(3)
                startPairing()
            } else {
                showTokenStatus("Invalid token. Check it and try again.", false)
                btn.isEnabled = true
            }
        }
    }

    private fun showTokenStatus(msg: String, success: Boolean) {
        val tv = findViewById<TextView>(R.id.tvTokenStatus)
        tv.text = msg
        tv.setTextColor(if (success) 0xFF00CC88.toInt() else 0xFFFF6B6B.toInt())
    }

    // ── Step 3: Auto-detect Chat ID ──────────

    private fun setupStep3() {
        findViewById<Button>(R.id.btnOpenBot).setOnClickListener {
            if (botUsername.isNotBlank()) {
                try {
                    // Use the actual bot username to open in Telegram
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=$botUsername")))
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/$botUsername")))
                    } catch (_: Exception) {
                        Toast.makeText(this, "Open Telegram and search for @$botUsername", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                Toast.makeText(this, "Go back and verify your token first", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startPairing() {
        val progress = findViewById<ProgressBar>(R.id.progressPairing)
        val result = findViewById<TextView>(R.id.tvPairResult)
        val nextBtn = findViewById<Button>(R.id.btnStep3Next)
        val instructions = findViewById<TextView>(R.id.tvPairInstructions)

        progress.visibility = View.VISIBLE
        result.text = ""
        instructions.text = "Open Telegram and send /start to your bot @$botUsername.\n\nWaiting for your message..."

        scope.launch {
            val detector = ChatIdDetector(botToken)
            val user = detector.waitForStart(timeout = 120_000)

            progress.visibility = View.GONE
            if (user != null) {
                chatId = user.chatId
                result.text = "Paired with ${user.firstName}" +
                        (if (user.username != null) " (@${user.username})" else "")
                result.setTextColor(0xFF00CC88.toInt())
                nextBtn.visibility = View.VISIBLE
                nextBtn.setOnClickListener { showStep(4) }
            } else {
                result.text = "Timed out. Make sure you sent /start to the right bot."
                result.setTextColor(0xFFFF6B6B.toInt())
                instructions.text = "Tap the button below to open your bot, then send /start."
                nextBtn.visibility = View.VISIBLE
                nextBtn.text = "Retry"
                nextBtn.setOnClickListener {
                    nextBtn.visibility = View.GONE
                    startPairing()
                }
            }
        }
    }

    // ── Step 4: AI Provider + API Key + Permission Mode ──

    private var selectedProvider = AIProvider.GEMINI
    private var askPermission = true  // Default: ask before actions

    private fun setupStep4() {
        val btnGemini = findViewById<Button>(R.id.btnPickGemini)
        val btnClaude = findViewById<Button>(R.id.btnPickClaude)
        val btnOpenAI = findViewById<Button>(R.id.btnPickOpenAI)
        val instructions = findViewById<TextView>(R.id.tvAIInstructions)
        val openBtn = findViewById<Button>(R.id.btnOpenClaude)
        val keyField = findViewById<EditText>(R.id.etClaudeKey)

        fun resetProviderButtons() {
            listOf(btnGemini, btnClaude, btnOpenAI).forEach { btn ->
                btn?.setBackgroundColor(0xFF1E1E1E.toInt())
                btn?.setTextColor(0xFFAAAAAA.toInt())
            }
        }

        fun selectGemini() {
            selectedProvider = AIProvider.GEMINI
            resetProviderButtons()
            btnGemini.setBackgroundColor(0xFF1A73E8.toInt())
            btnGemini.setTextColor(0xFFFFFFFF.toInt())
            instructions.text = "1. Go to aistudio.google.com/apikey\n2. Sign in with Google\n3. Click 'Create API Key'\n4. Copy and paste below"
            openBtn.text = "Get Free Gemini API Key"
            keyField.hint = "AIzaSy..."
        }

        fun selectClaude() {
            selectedProvider = AIProvider.CLAUDE
            resetProviderButtons()
            btnClaude.setBackgroundColor(0xFFCC7000.toInt())
            btnClaude.setTextColor(0xFFFFFFFF.toInt())
            instructions.text = "1. Go to console.anthropic.com\n2. Sign up / Log in\n3. Go to API Keys -> Create key\n4. Copy and paste below\n\nNote: Claude is paid (~\$0.25/1000 commands)"
            openBtn.text = "Open console.anthropic.com"
            keyField.hint = "sk-ant-api03-..."
        }

        fun selectOpenAI() {
            selectedProvider = AIProvider.OPENAI
            resetProviderButtons()
            btnOpenAI?.setBackgroundColor(0xFF10A37F.toInt())
            btnOpenAI?.setTextColor(0xFFFFFFFF.toInt())
            instructions.text = "1. Go to platform.openai.com/api-keys\n2. Sign up / Log in\n3. Create a new API key\n4. Copy and paste below\n\nNote: ChatGPT is paid (~\$0.15/1000 commands)"
            openBtn.text = "Get ChatGPT API Key"
            keyField.hint = "sk-proj-..."
        }

        btnGemini.setOnClickListener { selectGemini() }
        btnClaude.setOnClickListener { selectClaude() }
        btnOpenAI?.setOnClickListener { selectOpenAI() }
        selectGemini()

        openBtn.setOnClickListener {
            val url = when (selectedProvider) {
                AIProvider.GEMINI -> "https://aistudio.google.com/apikey"
                AIProvider.CLAUDE -> "https://console.anthropic.com/account/keys"
                AIProvider.OPENAI -> "https://platform.openai.com/api-keys"
            }
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        // Permission mode toggle
        val btnAskMode = findViewById<Button>(R.id.btnAskPermission)
        val btnJustDoIt = findViewById<Button>(R.id.btnJustDoIt)

        fun selectAskMode() {
            askPermission = true
            btnAskMode.setBackgroundColor(0xFF00CC88.toInt())
            btnAskMode.setTextColor(0xFF000000.toInt())
            btnJustDoIt.setBackgroundColor(0xFF1E1E1E.toInt())
            btnJustDoIt.setTextColor(0xFFAAAAAA.toInt())
        }

        fun selectJustDoIt() {
            askPermission = false
            btnJustDoIt.setBackgroundColor(0xFFFF4500.toInt())
            btnJustDoIt.setTextColor(0xFFFFFFFF.toInt())
            btnAskMode.setBackgroundColor(0xFF1E1E1E.toInt())
            btnAskMode.setTextColor(0xFFAAAAAA.toInt())
        }

        btnAskMode?.setOnClickListener { selectAskMode() }
        btnJustDoIt?.setOnClickListener { selectJustDoIt() }
        selectAskMode()

        findViewById<Button>(R.id.btnStep4Next).setOnClickListener {
            val key = keyField.text.toString().trim()
            if (key.isBlank()) {
                Toast.makeText(this, "Paste your API key.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            getSharedPreferences("bharatdroid", MODE_PRIVATE).edit()
                .putString("bot_token", botToken)
                .putString("ai_key", key)
                .putString("ai_provider", selectedProvider.name)
                .putLong("chat_id", chatId)
                .putBoolean("ask_permission", askPermission)
                .putBoolean("onboarded", true)
                .apply()

            showStep(5)
        }
    }

    // ── Step 5: Accessibility + Notification Access + Launch ────────

    private fun setupStep5() {
        findViewById<Button>(R.id.btnEnableAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnEnableNotifAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<Button>(R.id.btnLaunchAgent).setOnClickListener {
            if (!AgentAccessibilityService.isConnected) {
                Toast.makeText(this, "Enable Accessibility Service first!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startForegroundService(Intent(this, AgentForegroundService::class.java))
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    // ── Step Navigation ──────────────────────

    private fun showStep(step: Int) {
        val steps = listOf(R.id.step1, R.id.step2, R.id.step3, R.id.step4, R.id.step5)
        steps.forEachIndexed { i, id ->
            findViewById<View>(id).visibility = if (i == step - 1) View.VISIBLE else View.GONE
        }
        findViewById<TextView>(R.id.tvStepIndicator).text = "Step $step of 5"
    }
}
