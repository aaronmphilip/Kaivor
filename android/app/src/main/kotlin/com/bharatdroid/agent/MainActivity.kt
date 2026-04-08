package com.bharatdroid.agent

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

// ─────────────────────────────────────────────
// MAIN ACTIVITY — Setup Screen
//
// One-time setup:
//   1. Enter Telegram Bot Token
//   2. Enter Claude API Key
//   3. Enter your Telegram Chat ID
//   4. Enable Accessibility Service
//   5. Tap "Start Agent"
// ─────────────────────────────────────────────

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("bharatdroid", MODE_PRIVATE)

        val etBotToken = findViewById<EditText>(R.id.etBotToken)
        val etClaudeKey = findViewById<EditText>(R.id.etClaudeKey)
        val etChatId = findViewById<EditText>(R.id.etChatId)
        val btnAccessibility = findViewById<Button>(R.id.btnAccessibility)
        val btnStart = findViewById<Button>(R.id.btnStart)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)

        // Pre-fill saved values
        etBotToken.setText(prefs.getString("bot_token", ""))
        etClaudeKey.setText(prefs.getString("claude_key", ""))
        val savedChatId = prefs.getLong("chat_id", -1L)
        if (savedChatId != -1L) etChatId.setText(savedChatId.toString())

        // Open accessibility settings
        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Start agent
        btnStart.setOnClickListener {
            val botToken = etBotToken.text.toString().trim()
            val claudeKey = etClaudeKey.text.toString().trim()
            val chatIdStr = etChatId.text.toString().trim()

            if (botToken.isBlank() || claudeKey.isBlank() || chatIdStr.isBlank()) {
                Toast.makeText(this, "Fill in all fields.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!AgentAccessibilityService.isConnected) {
                Toast.makeText(this, "Enable Accessibility Service first.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val chatId = chatIdStr.toLongOrNull()
            if (chatId == null) {
                Toast.makeText(this, "Chat ID must be a number.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Save config
            prefs.edit()
                .putString("bot_token", botToken)
                .putString("claude_key", claudeKey)
                .putLong("chat_id", chatId)
                .apply()

            // Start foreground service
            startForegroundService(Intent(this, AgentForegroundService::class.java))
            tvStatus.text = "Agent running. Go to Telegram and say hi!"
        }
    }

    override fun onResume() {
        super.onResume()
        val tvAccessStatus = findViewById<TextView>(R.id.tvAccessibilityStatus)
        tvAccessStatus.text = if (AgentAccessibilityService.isConnected) {
            "✓ Accessibility Service: Connected"
        } else {
            "✗ Accessibility Service: Not connected"
        }
    }
}
