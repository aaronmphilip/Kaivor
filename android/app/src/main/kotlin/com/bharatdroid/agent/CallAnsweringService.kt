package com.bharatdroid.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioManager
import android.telecom.Call
import android.telecom.InCallService
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/**
 * InCallService — BharatDroid's AI call answering engine.
 *
 * Flow:
 *   Call rings → VIP check → if VIP: notify owner to call back → else: AI answers
 *   AI answers: alert Telegram → auto-answer → speaker at 40% → CallAudioBridge loop
 *   Call ends: summary sent to Telegram
 *   Owner can tap "Take Over" anytime → AI stops, normal earpiece/mic resumes
 *
 * Requires user to set BharatDroid as the default Phone app once (Settings → Set as Default Phone App).
 * Config injected by AgentOrchestrator.start().
 */
class CallAnsweringService : InCallService() {

    companion object {
        const val CALL_CHANNEL   = "bharatdroid_calls"
        const val CALL_NOTIF_ID  = 42
        const val ACTION_TAKE_OVER  = "com.bharatdroid.agent.TAKE_OVER"
        const val ACTION_HANG_UP    = "com.bharatdroid.agent.HANG_UP"

        @Volatile var config: AgentConfig? = null
        @Volatile var orchestrator: AgentOrchestrator? = null
        @Volatile var activeInstance: CallAnsweringService? = null

        /** Called from AgentOrchestrator when owner Telegrams "hang up" or "reject call". */
        fun hangUpCurrentCall() { activeInstance?.hangUpNow() }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeCall: Call? = null
    private var conversationSoFar = ""   // snapshot for owner-request context

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            if (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING) {
                tearDown()
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val cfg = config ?: return
        if (!cfg.callAnsweringEnabled) return
        if (activeCall != null) return

        // Skip outgoing calls — only intercept incoming
        if (call.details?.callDirection == android.telecom.Call.Details.DIRECTION_OUTGOING) return

        activeCall = call
        call.registerCallback(callCallback)

        val callerNumber  = call.details?.handle?.schemeSpecificPart?.filter { it.isDigit() || it == '+' } ?: ""
        val callerDisplay = call.details?.callerDisplayName?.takeIf { it.isNotBlank() } ?: callerNumber.ifBlank { "Unknown" }

        // ── VIP check: forward important callers instead of letting AI handle ──
        if (isVip(callerNumber, cfg)) {
            scope.launch {
                cfg.authorizedChatIds.firstOrNull()?.let { cid ->
                    orchestrator?.sendAlert(cid,
                        "🔴 *VIP CALL* from *${md(callerDisplay)}* \\(${md(callerNumber)}\\)\\!\n" +
                        "AI is NOT answering\\. Call them back now\\."
                    )
                }
                // Don't answer — let it ring so the caller can leave a voicemail naturally
            }
            activeCall?.unregisterCallback(callCallback)
            activeCall = null
            return
        }

        // ── Regular call: AI handles it ───────────────────────────────────────
        scope.launch {
            cfg.authorizedChatIds.firstOrNull()?.let { cid ->
                orchestrator?.sendAlert(cid,
                    "📞 Incoming call from *${md(callerDisplay)}*\n🤖 AI answering\\. Transcript incoming\\."
                )
            }

            delay(1_800)   // brief ring so caller doesn't think it's dead
            withContext(Dispatchers.Main) {
                if (call.state == Call.STATE_RINGING) {
                    call.answer(0)
                    (getSystemService(AUDIO_SERVICE) as AudioManager).isSpeakerphoneOn = true
                }
            }
            delay(700)
            if (call.state == Call.STATE_ACTIVE) {
                startBridge(callerDisplay, callerNumber, cfg)
            }
        }

        showTakeOverNotification(callerDisplay)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        if (call == activeCall) tearDown()
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TAKE_OVER -> tearDown()
            ACTION_HANG_UP   -> hangUpNow()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        tearDown()
        scope.cancel()
        super.onDestroy()
    }

    fun hangUpNow() {
        CallAudioBridge.instance?.stop()
        activeCall?.disconnect()
        tearDown()
    }

    // ── VIP routing ───────────────────────────────────────────────────────────

    private fun isVip(callerNumber: String, cfg: AgentConfig): Boolean {
        if (cfg.vipCallerNumbers.isBlank() || callerNumber.isBlank()) return false
        val callerLast10 = callerNumber.filter { it.isDigit() }.takeLast(10)
        return cfg.vipCallerNumbers
            .split(",")
            .map { it.trim().filter { c -> c.isDigit() }.takeLast(10) }
            .any { it == callerLast10 && it.isNotBlank() }
    }

    // ── Bridge ────────────────────────────────────────────────────────────────

    private fun startBridge(callerDisplay: String, callerNumber: String, cfg: AgentConfig) {
        val sttKey = cfg.ttsApiKey.ifBlank {
            if (cfg.agentProvider == AIProvider.OPENAI) cfg.agentApiKey else ""
        }
        val chatId = cfg.authorizedChatIds.firstOrNull() ?: return

        CallAudioBridge(
            context = this,
            elevenLabsKey = cfg.elevenLabsApiKey,
            elevenLabsVoiceId = cfg.elevenLabsVoiceId,
            sttApiKey = sttKey,
            llmKey = cfg.agentApiKey,
            llmProvider = cfg.agentProvider,
            llmModel = cfg.agentModel,
            ownerName = cfg.ownerName,
            callerName = callerDisplay,
            onTranscript = { caller, ai ->
                // Keep a rolling snapshot so owner-request alert has context
                conversationSoFar = (conversationSoFar + "\n${md(caller)}").takeLast(600)
                scope.launch {
                    orchestrator?.sendAlert(chatId,
                        "📞 *${md(callerDisplay)}*: ${md(caller)}\n🤖 *AI*: ${md(ai)}"
                    )
                }
            },
            onOwnerRequested = {
                val context = conversationSoFar.trim()
                val contextLine = if (context.isNotBlank())
                    "\n\n*So far they said:*\n${context}" else ""
                orchestrator?.sendAlert(chatId,
                    "🔴 *${md(callerDisplay)}* wants to speak to you directly\\!$contextLine\n\n" +
                    "Tap *Take Over* in the notification to join\\. " +
                    "Or reply *hang up* to end the call\\."
                )
            },
            onCallEnded = { transcript, summary ->
                // Send full transcript first, then summary
                if (transcript.isNotBlank()) {
                    orchestrator?.sendAlert(chatId,
                        "📋 *Full transcript — ${md(callerDisplay)}*\n\n${md(transcript)}"
                    )
                }
                orchestrator?.sendAlert(chatId,
                    "📝 *Call summary*\n${md(summary)}"
                )
            },
        ).start()
    }

    private fun tearDown() {
        CallAudioBridge.instance?.stop()
        cancelNotification()
        // Restore earpiece mode
        (getSystemService(AUDIO_SERVICE) as AudioManager).isSpeakerphoneOn = false
        activeCall?.unregisterCallback(callCallback)
        activeCall = null
        scope.coroutineContext.cancelChildren()
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun showTakeOverNotification(callerName: String) {
        createChannel()
        val takeOverPi = PendingIntent.getService(
            this, 0,
            Intent(this, CallAnsweringService::class.java).setAction(ACTION_TAKE_OVER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hangUpPi = PendingIntent.getService(
            this, 1,
            Intent(this, CallAnsweringService::class.java).setAction(ACTION_HANG_UP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(this, CALL_CHANNEL)
            .setContentTitle("AI answering: $callerName")
            .setContentText("Take Over to join • Hang Up to end")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(android.R.drawable.ic_menu_call, "Take Over", takeOverPi)
            .addAction(android.R.drawable.ic_delete, "Hang Up", hangUpPi)
            .build()
        getSystemService(NotificationManager::class.java).notify(CALL_NOTIF_ID, notif)
    }

    private fun cancelNotification() =
        getSystemService(NotificationManager::class.java).cancel(CALL_NOTIF_ID)

    private fun createChannel() {
        val ch = NotificationChannel(CALL_CHANNEL, "BharatDroid Calls",
            NotificationManager.IMPORTANCE_HIGH)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun md(s: String) = s.replace("_", "\\_").replace("*", "\\*").replace("`", "\\`")
}
