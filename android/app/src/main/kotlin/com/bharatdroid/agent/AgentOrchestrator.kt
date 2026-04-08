package com.bharatdroid.agent

import android.content.Context
import com.bharatdroid.agent.skills.SkillResult
import com.bharatdroid.agent.skills.builtin.SwigySkill
import com.bharatdroid.agent.skills.builtin.YouTubeSkill
import com.bharatdroid.agent.skills.builtin.ZeptoSkill
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────
// AGENT ORCHESTRATOR
//
// Wires together:
//   TelegramPoller → AIBrain → SkillRunner
//
// Also handles the YES/NO confirmation flow:
// when a skill needs user confirmation, the
// orchestrator pauses, asks via Telegram, and
// waits for the next message.
// ─────────────────────────────────────────────

class AgentOrchestrator(
    private val context: Context,
    private val config: AgentConfig,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Pending confirmation requests keyed by chatId
    private val pendingConfirmations = mutableMapOf<Long, CompletableDeferred<Boolean>>()

    private lateinit var poller: TelegramPoller
    private lateinit var brain: AIBrain
    private lateinit var skillRunner: SkillRunner

    fun start() {
        skillRunner = SkillRunner(
            context = context,
            requestConfirmation = { chatId, question ->
                poller.sendMessage(chatId, question)
                // Park a deferred — next message from this user resolves it
                val deferred = CompletableDeferred<Boolean>()
                pendingConfirmations[chatId] = deferred
                deferred.await()
            }
        ).also { runner ->
            runner.register(SwigySkill())
            runner.register(ZeptoSkill())
            runner.register(YouTubeSkill())
            // Community skills loaded from storage would go here
        }

        brain = AIBrain(
            claudeApiKey = config.claudeApiKey,
            availableSkills = skillRunner.getSkillInfoForAI(),
        )

        poller = TelegramPoller(
            botToken = config.telegramBotToken,
            authorizedChatIds = config.authorizedChatIds,
            onMessage = ::handleMessage,
        )

        poller.start()
    }

    fun stop() {
        poller.stop()
    }

    private suspend fun handleMessage(msg: IncomingMessage): String {
        // ── Handle built-in commands ──
        when (msg.text.trim().lowercase()) {
            "/start" -> return buildWelcomeMessage()
            "/skills" -> return buildSkillsMessage()
            "/status" -> return buildStatusMessage()
            "/clear" -> {
                brain.clearHistory(msg.chatId)
                return "Memory cleared. Fresh start."
            }
        }

        // ── Resolve pending confirmation if one is waiting ──
        pendingConfirmations.remove(msg.chatId)?.let { deferred ->
            val confirmed = msg.text.trim().uppercase() == "YES"
            deferred.complete(confirmed)
            return if (confirmed) "Got it. Proceeding..." else "Cancelled."
        }

        // ── Normal flow: AI → Skill ──
        poller.sendTyping(msg.chatId)

        val plan = brain.process(msg.chatId, msg.text)

        return when (plan.type) {
            PlanType.DIRECT_REPLY -> plan.directReply ?: "?"

            PlanType.RUN_SKILL -> {
                val result = skillRunner.execute(
                    skillId = plan.skillId!!,
                    params = plan.params,
                    chatId = msg.chatId,
                    userId = msg.username ?: msg.chatId.toString(),
                )
                when (result) {
                    is SkillResult.Success -> result.message
                    is SkillResult.Failure -> "Failed: ${result.reason}"
                    is SkillResult.NeedsConfirmation -> result.prompt // handled by SkillRunner
                }
            }

            PlanType.UNKNOWN -> plan.directReply ?: "I didn't understand that. Try again."
        }
    }

    private fun buildWelcomeMessage(): String = """
*BharatDroid Agent* is running on this phone.

I can control apps on your behalf. Just tell me what to do in plain English or Hindi.

Examples:
• "Order biryani from Swiggy under ₹200"
• "Get me 2 litres of milk from Zepto"
• "Play Arijit Singh on YouTube"
• "What's the weather in Mumbai?"

Type /skills to see all available skills.
    """.trimIndent()

    private fun buildSkillsMessage(): String {
        val skills = skillRunner.listSkills()
        if (skills.isEmpty()) return "No skills loaded."
        return "*Available Skills:*\n\n" + skills.joinToString("\n\n") { s ->
            "*${s.name}* (${s.id})\n${s.description}\n" +
            if (!s.trusted) "⚠️ Community skill — needs your approval to run." else "✓ Official skill"
        }
    }

    private fun buildStatusMessage(): String {
        val serviceOk = AgentAccessibilityService.isConnected
        return """
*BharatDroid Status*

Agent: Running
Accessibility Service: ${if (serviceOk) "✓ Connected" else "✗ Not connected (open app to fix)"}
Skills Loaded: ${skillRunner.listSkills().size}
        """.trimIndent()
    }
}

data class AgentConfig(
    val telegramBotToken: String,
    val claudeApiKey: String,
    val authorizedChatIds: Set<Long>,
)
