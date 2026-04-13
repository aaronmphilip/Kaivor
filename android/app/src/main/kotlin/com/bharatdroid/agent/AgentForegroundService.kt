package com.bharatdroid.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService

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
        val aiKey = prefs.getString("ai_key", null)
            ?: prefs.getString("claude_key", null) // Backwards compatible
        val chatId = prefs.getLong("chat_id", -1L)

        if (botToken == null || aiKey == null || chatId == -1L) {
            stopSelf()
            return START_NOT_STICKY
        }

        val askPerm = prefs.getBoolean("ask_permission", true)
        val providerStr = prefs.getString("ai_provider", null) ?: ""
        val aiModel = prefs.getString("ai_model", "") ?: ""

        // Determine provider: saved preference > auto-detect from key
        val aiProvider = try {
            if (providerStr.isNotBlank()) AIProvider.valueOf(providerStr)
            else AIBrain.detectProvider(aiKey)
        } catch (_: Exception) {
            AIBrain.detectProvider(aiKey)
        }

        val config = AgentConfig(
            telegramBotToken = botToken,
            claudeApiKey = aiKey,
            authorizedChatIds = setOf(chatId),
            askPermission = askPerm,
            aiProvider = aiProvider,
            aiModel = aiModel,
        )

        orchestrator = AgentOrchestrator(this, config)
        orchestrator.start()

        return START_STICKY
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
