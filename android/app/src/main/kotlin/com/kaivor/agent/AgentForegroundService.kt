package com.kaivor.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.BatteryManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AgentForegroundService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "kaivor_agent"
        const val NOTIFICATION_ID = 1

        var orchestratorInstance: AgentOrchestrator? = null
        var serviceScope: CoroutineScope? = null
    }

    private lateinit var orchestrator: AgentOrchestrator
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val prefs = getSharedPreferences("kaivor", MODE_PRIVATE)
        val botToken = prefs.getString("bot_token", null)
        val agentKey = prefs.getString("agent_ai_key", null)
            ?: prefs.getString("ai_key", null)
            ?: prefs.getString("claude_key", null)
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
        val researchProviderStr = if (rawResearchKey.isBlank()) agentProviderStr
            else prefs.getString("research_ai_provider", null) ?: ""
        val researchModel = if (rawResearchKey.isBlank()) agentModel
            else prefs.getString("research_ai_model", "") ?: ""

        val agentProvider = try {
            if (agentProviderStr.isNotBlank()) AIProvider.valueOf(agentProviderStr)
            else AIBrain.detectProvider(agentKey)
        } catch (_: Exception) { AIBrain.detectProvider(agentKey) }

        val researchProvider = try {
            if (researchProviderStr.isNotBlank()) AIProvider.valueOf(researchProviderStr)
            else AIBrain.detectProvider(researchKey)
        } catch (_: Exception) { AIBrain.detectProvider(researchKey) }

        // Optional TTS — voice replies for /info and /research.
        val ttsKey = prefs.getString("tts_api_key", "")?.trim().orEmpty()
        val ttsEnabled = prefs.getBoolean("tts_enabled", false) && ttsKey.isNotBlank()
        val ttsProvider = if (ttsEnabled) TtsProvider.OPENAI else TtsProvider.OFF
        val ttsVoice = prefs.getString("tts_voice", "alloy") ?: "alloy"

        // Optional Image Generation — "generate an image of X" sends photo in Telegram.
        val imageApiKey = prefs.getString("image_api_key", "")?.trim().orEmpty()
        val imageApiProvider = prefs.getString("image_api_provider", "together") ?: "together"

        // One fast execution path. The screen agent decides when vision is needed.
        val ultraMode = false
        val whatsappChannelNumber = prefs.getString("whatsapp_channel_number", "")?.trim().orEmpty()
        val callAnsweringEnabled = prefs.getBoolean("call_answering_enabled", false)
        val elevenLabsApiKey = prefs.getString("elevenlabs_api_key", "")?.trim().orEmpty()
        val elevenLabsVoiceId = prefs.getString("elevenlabs_voice_id", "")?.trim().orEmpty()
        val ownerName = prefs.getString("owner_name", "")?.trim().orEmpty()
        val vipCallerNumbers = prefs.getString("vip_caller_numbers", "")?.trim().orEmpty()

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
            ttsProvider = ttsProvider,
            ttsApiKey = ttsKey,
            ttsVoice = ttsVoice,
            imageApiKey = imageApiKey,
            imageApiProvider = imageApiProvider,
            ultraMode = ultraMode,
            whatsappChannelNumber = whatsappChannelNumber,
            callAnsweringEnabled = callAnsweringEnabled,
            elevenLabsApiKey = elevenLabsApiKey,
            elevenLabsVoiceId = elevenLabsVoiceId,
            ownerName = ownerName,
            vipCallerNumbers = vipCallerNumbers,
        )

        orchestrator = AgentOrchestrator(this, config)
        orchestratorInstance = orchestrator
        serviceScope = scope
        orchestrator.start()

        NotificationRelay.rebind(this)
        startBatteryMonitor(chatId)

        return START_STICKY
    }

    override fun onDestroy() {
        orchestratorInstance = null
        serviceScope = null
        if (::orchestrator.isInitialized) orchestrator.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null

    private fun startBatteryMonitor(chatId: Long) {
        scope.launch {
            var lastAlertedLevel = 101
            delay(2 * 60 * 1000L) // wait 2 min after start before first check
            while (true) {
                val level = getBatteryLevel()
                if (level in 1..15 && level < lastAlertedLevel) {
                    val icon = when {
                        level <= 5 -> "🪫"
                        level <= 10 -> "🔴"
                        else -> "🔋"
                    }
                    orchestratorInstance?.sendAlert(
                        chatId,
                        "$icon Battery at *$level%* — please plug in your phone!"
                    )
                    lastAlertedLevel = level
                } else if (level > 20) {
                    lastAlertedLevel = 101 // reset once charged
                }
                delay(5 * 60 * 1000L) // check every 5 minutes
            }
        }
    }

    private fun getBatteryLevel(): Int {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kaivor Agent")
            .setContentText("Listening for your Telegram commands...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Kaivor Agent",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the AI agent running in background"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
