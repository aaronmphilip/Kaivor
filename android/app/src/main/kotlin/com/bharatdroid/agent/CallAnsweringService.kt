package com.bharatdroid.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.telecom.Call
import android.telecom.InCallService
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/**
 * InCallService — BharatDroid's AI call answering + outbound calling engine.
 *
 * INBOUND flow:
 *   Call rings → VIP check → if VIP: notify owner → else: AI answers
 *   AI answers: alert Telegram → auto-answer → speaker 40% → CallAudioBridge loop
 *   Call ends: full transcript + LLM summary sent to Telegram
 *   Owner can tap "Take Over" anytime → AI stops, normal earpiece/mic resumes
 *
 * OUTBOUND flow ("go talk to them" superpower):
 *   Owner Telegrams "call Rahul about the payment" →
 *   AgentOrchestrator sets pendingOutbound → places ACTION_CALL →
 *   onCallAdded detects pendingOutbound → registers callback for STATE_ACTIVE →
 *   On connect: CallAudioBridge starts with isOutbound=true + briefing →
 *   AI introduces itself, conducts conversation, live-transcripts to Telegram →
 *   [ASK_OWNER: question] → blocks, owner replies via Telegram → fed back to AI →
 *   [CALL_END] → AI says goodbye → call.disconnect()
 *
 * Requires BharatDroid set as default Phone app (Settings → Set as Default Phone App).
 */
class CallAnsweringService : InCallService() {

    companion object {
        const val CALL_CHANNEL      = "bharatdroid_calls"
        const val CALL_NOTIF_ID     = 42
        const val ACTION_TAKE_OVER  = "com.bharatdroid.agent.TAKE_OVER"
        const val ACTION_HANG_UP    = "com.bharatdroid.agent.HANG_UP"

        @Volatile var config: AgentConfig? = null
        @Volatile var orchestrator: AgentOrchestrator? = null
        @Volatile var activeInstance: CallAnsweringService? = null

        /**
         * Set this BEFORE placing the outbound call (Intent.ACTION_CALL).
         * onCallAdded consumes it when it sees an outgoing call while this is set.
         */
        @Volatile var pendingOutbound: OutboundSession? = null

        /** Called from AgentOrchestrator when owner Telegrams "hang up". */
        fun hangUpCurrentCall() { activeInstance?.hangUpNow() }

        /**
         * Feed the owner's Telegram reply into a suspended [ASK_OWNER] relay.
         * Returns true if there was a pending query waiting for this reply.
         */
        fun provideOwnerReply(reply: String): Boolean {
            val deferred = activeInstance?.pendingOwnerQuery ?: return false
            return if (!deferred.isCompleted) { deferred.complete(reply); true } else false
        }

    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeCall: Call? = null
    private var activeCfg: AgentConfig? = null
    private var conversationSoFar = ""          // rolling snapshot for owner-request alert
    private var pendingOutboundSession: OutboundSession? = null  // consumed in STATE_ACTIVE

    /** Suspended by [ASK_OWNER:] sentinel; completed when owner sends Telegram reply. */
    @Volatile var pendingOwnerQuery: CompletableDeferred<String>? = null

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            when (state) {
                // Outbound call connected — start the AI bridge now
                Call.STATE_ACTIVE -> {
                    val session = pendingOutboundSession
                    val cfg = activeCfg
                    if (session != null && cfg != null && call == activeCall) {
                        pendingOutboundSession = null
                        scope.launch {
                            withContext(Dispatchers.Main) {
                                (getSystemService(AUDIO_SERVICE) as AudioManager)
                                    .isSpeakerphoneOn = true
                            }
                            delay(500)
                            startOutboundBridge(session, cfg)
                        }
                    }
                }
                Call.STATE_DISCONNECTED,
                Call.STATE_DISCONNECTING -> tearDown()
            }
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val cfg = config ?: return
        if (!cfg.callAnsweringEnabled) return
        if (activeCall != null) return

        val isOutgoing = call.details?.callDirection == android.telecom.Call.Details.DIRECTION_OUTGOING
        val outboundSession = pendingOutbound

        // ── AI-initiated outbound call ─────────────────────────────────────────
        if (isOutgoing && outboundSession != null) {
            pendingOutbound = null   // consume immediately
            activeCall = call
            activeCfg = cfg
            call.registerCallback(callCallback)
            pendingOutboundSession = outboundSession
            showOutboundNotification(outboundSession.targetName)

            // If the call is already active (e.g. on some devices it goes straight to ACTIVE),
            // start the bridge right away; otherwise the callback handles it.
            if (call.state == Call.STATE_ACTIVE) {
                pendingOutboundSession = null
                scope.launch {
                    withContext(Dispatchers.Main) {
                        (getSystemService(AUDIO_SERVICE) as AudioManager).isSpeakerphoneOn = true
                    }
                    delay(500)
                    startOutboundBridge(outboundSession, cfg)
                }
            }
            return
        }

        // ── Skip non-AI outgoing calls ─────────────────────────────────────────
        if (isOutgoing) return

        // ── Incoming call ──────────────────────────────────────────────────────
        activeCall = call
        activeCfg = cfg
        call.registerCallback(callCallback)

        val callerNumber  = call.details?.handle?.schemeSpecificPart
            ?.filter { it.isDigit() || it == '+' } ?: ""
        val callerDisplay = call.details?.callerDisplayName
            ?.takeIf { it.isNotBlank() } ?: callerNumber.ifBlank { "Unknown" }

        // VIP check — alert owner, don't answer
        if (isVip(callerNumber, cfg)) {
            scope.launch {
                cfg.authorizedChatIds.firstOrNull()?.let { cid ->
                    orchestrator?.sendAlert(cid,
                        "🔴 *VIP CALL* from *${md(callerDisplay)}* \\(${md(callerNumber)}\\)\\!\n" +
                        "AI is NOT answering\\. Call them back now\\."
                    )
                }
            }
            activeCall?.unregisterCallback(callCallback)
            activeCall = null
            activeCfg = null
            return
        }

        // Regular call — AI handles it
        scope.launch {
            cfg.authorizedChatIds.firstOrNull()?.let { cid ->
                orchestrator?.sendAlert(cid,
                    "📞 Incoming call from *${md(callerDisplay)}*\n🤖 AI answering\\. Transcript incoming\\."
                )
            }
            delay(1_800)
            withContext(Dispatchers.Main) {
                if (call.state == Call.STATE_RINGING) {
                    call.answer(0)
                    (getSystemService(AUDIO_SERVICE) as AudioManager).isSpeakerphoneOn = true
                }
            }
            delay(700)
            if (call.state == Call.STATE_ACTIVE) {
                startInboundBridge(callerDisplay, callerNumber, cfg)
            }
        }

        showTakeOverNotification(callerDisplay)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        if (call == activeCall) tearDown()
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
        pendingOwnerQuery?.cancel()
        pendingOwnerQuery = null
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

    // ── Inbound bridge ────────────────────────────────────────────────────────

    private fun startInboundBridge(callerDisplay: String, callerNumber: String, cfg: AgentConfig) {
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
                conversationSoFar = (conversationSoFar + "\n${md(caller)}").takeLast(600)
                scope.launch {
                    if (caller.isNotBlank() || ai.isNotBlank()) {
                        orchestrator?.sendAlert(chatId,
                            "📞 *${md(callerDisplay)}*: ${md(caller)}\n🤖 *AI*: ${md(ai)}"
                        )
                    }
                }
            },
            onOwnerRequested = {
                val ctx = conversationSoFar.trim()
                val ctxLine = if (ctx.isNotBlank()) "\n\n*So far they said:*\n${ctx}" else ""
                orchestrator?.sendAlert(chatId,
                    "🔴 *${md(callerDisplay)}* wants to speak to you directly\\!$ctxLine\n\n" +
                    "Tap *Take Over* in the notification to join\\. " +
                    "Or reply *hang up* to end the call\\."
                )
            },
            onCallEnded = { transcript, summary ->
                if (transcript.isNotBlank()) {
                    orchestrator?.sendAlert(chatId,
                        "📋 *Full transcript — ${md(callerDisplay)}*\n\n${md(transcript)}"
                    )
                }
                orchestrator?.sendAlert(chatId, "📝 *Call summary*\n${md(summary)}")
            },
        ).start()
    }

    // ── Outbound bridge ───────────────────────────────────────────────────────

    private fun startOutboundBridge(session: OutboundSession, cfg: AgentConfig) {
        val sttKey = cfg.ttsApiKey.ifBlank {
            if (cfg.agentProvider == AIProvider.OPENAI) cfg.agentApiKey else ""
        }
        val chatId = session.chatId

        // Alert owner that the call connected and AI is now speaking
        scope.launch {
            orchestrator?.sendAlert(chatId,
                "📲 *Outbound call connected* to *${md(session.targetName)}*\n" +
                "🤖 AI is handling the conversation\\. Transcript below\\.\n\n" +
                "_Reply to this message at any time to feed information to the AI\\._\n" +
                "_When AI pauses and asks \\[you\\], just type your answer\\._"
            )
        }

        CallAudioBridge(
            context = this,
            elevenLabsKey = cfg.elevenLabsApiKey,
            elevenLabsVoiceId = cfg.elevenLabsVoiceId,
            sttApiKey = sttKey,
            llmKey = cfg.agentApiKey,
            llmProvider = cfg.agentProvider,
            llmModel = cfg.agentModel,
            ownerName = cfg.ownerName,
            callerName = session.targetName,
            isOutbound = true,
            outboundBriefing = session.briefing,
            onTranscript = { caller, ai ->
                scope.launch {
                    val callerLine = if (caller.isNotBlank()) "🧑 *${md(session.targetName)}*: ${md(caller)}\n" else ""
                    val aiLine     = if (ai.isNotBlank())     "🤖 *AI*: ${md(ai)}" else ""
                    if (callerLine.isNotBlank() || aiLine.isNotBlank()) {
                        orchestrator?.sendAlert(chatId, "$callerLine$aiLine".trim())
                    }
                }
            },
            onOwnerRequested = { /* not used in outbound */ },
            onOwnerQuery = { question ->
                // Alert owner with the question, suspend until they reply (up to 120s)
                orchestrator?.sendAlert(chatId,
                    "❓ *AI needs your input* for the call with *${md(session.targetName)}*:\n\n" +
                    "_${md(question)}_\n\n" +
                    "Type your answer and I'll relay it to the AI\\."
                )
                val deferred = CompletableDeferred<String>()
                pendingOwnerQuery = deferred
                try {
                    withTimeoutOrNull(120_000L) { deferred.await() } ?: "I'll need to check on that and get back to you."
                } finally {
                    pendingOwnerQuery = null
                }
            },
            onCallShouldEnd = {
                // AI said goodbye + [CALL_END] sentinel — disconnect cleanly
                scope.launch {
                    delay(800)
                    hangUpNow()
                }
            },
            onCallEnded = { transcript, summary ->
                if (transcript.isNotBlank()) {
                    orchestrator?.sendAlert(chatId,
                        "📋 *Full call transcript — ${md(session.targetName)}*\n\n${md(transcript)}"
                    )
                }
                orchestrator?.sendAlert(chatId, "📝 *Call summary*\n${md(summary)}")
            },
        ).start()
    }

    private fun tearDown() {
        pendingOwnerQuery?.cancel()
        pendingOwnerQuery = null
        CallAudioBridge.instance?.stop()
        cancelNotification()
        (getSystemService(AUDIO_SERVICE) as AudioManager).isSpeakerphoneOn = false
        activeCall?.unregisterCallback(callCallback)
        activeCall = null
        activeCfg = null
        pendingOutboundSession = null
        conversationSoFar = ""
        scope.coroutineContext.cancelChildren()
    }

    // ── Notifications ──────────────────────────────────────────────────────────

    private fun showTakeOverNotification(callerName: String) {
        createChannel()
        val takeOverPi = makePi(ACTION_TAKE_OVER, 0)
        val hangUpPi   = makePi(ACTION_HANG_UP, 1)
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

    private fun showOutboundNotification(targetName: String) {
        createChannel()
        val hangUpPi   = makePi(ACTION_HANG_UP, 1)
        val notif = NotificationCompat.Builder(this, CALL_CHANNEL)
            .setContentTitle("📲 AI calling: $targetName")
            .setContentText("AI conducting outbound call • Hang Up to end")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(android.R.drawable.ic_delete, "Hang Up", hangUpPi)
            .build()
        getSystemService(NotificationManager::class.java).notify(CALL_NOTIF_ID, notif)
    }

    private fun makePi(action: String, reqCode: Int) = PendingIntent.getService(
        this, reqCode,
        Intent(this, CallAnsweringService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelNotification() =
        getSystemService(NotificationManager::class.java).cancel(CALL_NOTIF_ID)

    private fun createChannel() {
        val ch = NotificationChannel(CALL_CHANNEL, "BharatDroid Calls",
            NotificationManager.IMPORTANCE_HIGH)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun md(s: String) = s.replace("_", "\\_").replace("*", "\\*").replace("`", "\\`")
}

/** One AI-initiated outbound call session. */
data class OutboundSession(
    val targetName: String,    // display name e.g. "Rahul" or the number
    val targetNumber: String,  // tel: number to dial
    val briefing: String,      // purpose — fed to LLM system prompt
    val chatId: Long,          // owner Telegram chat id for live transcript
)
