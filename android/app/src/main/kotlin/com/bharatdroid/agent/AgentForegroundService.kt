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

        var orchestratorInstance: AgentOrchestrator? = null
        var serviceScope: kotlinx.coroutines.CoroutineScope? = null
    }

    private lateinit var orchestrator: AgentOrchestrator
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val prefs = getSharedPreferences("bharatdroid", MODE_PRIVATE)
        val botToken = prefs.getString("bot_token", null)
        val agentKey = prefs.getString("agent_ai_key", null)
            ?: prefs.getString("ai_key", null)
            ?: prefs.getString("claude_key", null) // Backwards compatible
        val chatId = prefs.getLong("chat_id", -1L)

        if (botToken == null || agentKey == null || chatId == -1L) {
            stopSelf()
            return START_NOT_STICKY
        }

        val askPerm = prefs.getBoolean("ask_permission", true)
        val agentProviderStr = prefs.getString("agent_ai_provider", prefs.getString("ai_provider", null)) ?: ""
        val agentModel = prefs.getString("agent_ai_model", prefs.getString("ai_model", "")) ?: ""
        val rawResearchKey = prefs.getString("research_ai_key", null)?.trim().orEmpty()
        val researchKey = rawResearchKey.ifBlank { agentKey }
        val researchProviderStr = if (rawResearchKey.isBlank()) {
            agentProviderStr
        } else {
            prefs.getString("research_ai_provider", null) ?: ""
        }
        val researchModel = if (rawResearchKey.isBlank()) {
            agentModel
        } else {
            prefs.getString("research_ai_model", "") ?: ""
        }

        // Determine provider: saved preference > auto-detect from key
        val agentProvider = try {
            if (agentProviderStr.isNotBlank()) AIProvider.valueOf(agentProviderStr)
            else AIBrain.detectProvider(agentKey)
        } catch (_: Exception) {
            AIBrain.detectProvider(agentKey)
        }
        val researchProvider = try {
            if (researchProviderStr.isNotBlank()) AIProvider.valueOf(researchProviderStr)
            else AIBrain.detectProvider(researchKey)
        } catch (_: Exception) {
            AIBrain.detectProvider(researchKey)
        }

        val config = AgentConfig(
            telegramBotToken = botToken,
            agentApiKey = agentKey,
            researchApiKey = researchKey,
            authorizedChatIds = setOf(chatId),
            askPermission = askPerm,
            agentProvider = agentProvider,
            agentModel = agentModel,
            researchProvider = researchProvider,
            researchModel = researchModel,
        )

        orchestrator = AgentOrchestrator(this, config)
        orchestrator.start()
        orchestratorInstance = orchestrator
        serviceScope = scope

        // Kick the notification listener to rebind after service restarts / boot.
        NotificationRelay.rebind(this)

        return START_STICKY
    }

    override fun onDestroy() {
        orchestratorInstance = null
        serviceScope = null
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
