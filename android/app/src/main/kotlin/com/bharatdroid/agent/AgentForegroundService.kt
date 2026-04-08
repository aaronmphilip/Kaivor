package com.bharatdroid.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────
// FOREGROUND SERVICE
//
// Keeps the agent alive 24/7 in the background.
// Android would kill a normal background service.
// Foreground service shows a persistent notification
// so the OS keeps it alive.
// ─────────────────────────────────────────────

class AgentForegroundService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "bharatdroid_agent"
        const val NOTIFICATION_ID = 1
    }

    private lateinit var orchestrator: AgentOrchestrator

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val prefs = getSharedPreferences("bharatdroid", MODE_PRIVATE)
        val botToken = prefs.getString("bot_token", null)
        val claudeKey = prefs.getString("claude_key", null)
        val chatId = prefs.getLong("chat_id", -1L)

        if (botToken == null || claudeKey == null || chatId == -1L) {
            stopSelf()
            return START_NOT_STICKY
        }

        val config = AgentConfig(
            telegramBotToken = botToken,
            claudeApiKey = claudeKey,
            authorizedChatIds = setOf(chatId),
        )

        orchestrator = AgentOrchestrator(this, config)
        orchestrator.start()

        return START_STICKY // Restart if killed
    }

    override fun onDestroy() {
        if (::orchestrator.isInitialized) orchestrator.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BharatDroid Agent")
            .setContentText("Listening for your Telegram commands...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "BharatDroid Agent",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the AI agent running in background"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
