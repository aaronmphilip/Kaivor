package com.bharatdroid.agent

import android.content.Context
import com.bharatdroid.agent.skills.RemoteSkill
import com.bharatdroid.agent.skills.SkillResult
import com.bharatdroid.agent.skills.SkillStore
import com.bharatdroid.agent.skills.builtin.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AgentOrchestrator(
    private val context: Context,
    private val config: AgentConfig,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val skillStore = SkillStore(context)
    private val activityLog = ActivityLog(context)
    private val userMemory = UserMemory(context)
    private val appKnowledge = AppKnowledgeBase(context)
    private val pendingConfirmations = mutableMapOf<Long, CompletableDeferred<Boolean>>()

    private lateinit var poller: TelegramPoller
    private lateinit var brain: AIBrain
    private lateinit var skillRunner: SkillRunner

    fun start() {
        // Create the AI-driven screen agent — inject user memory so learned preferences
        // are automatically applied to every executeGoal call, without touching skill files
        val screenAgent = ScreenAgent(
            apiKey = config.claudeApiKey,
            provider = config.aiProvider,
            model = config.aiModel,
            userMemory = userMemory,
            appKnowledge = appKnowledge,
        )

        skillRunner = SkillRunner(
            context = context,
            askPermission = config.askPermission,
            requestConfirmation = { chatId, question ->
                if (!config.askPermission) {
                    true
                } else {
                    poller.sendMessage(chatId, question)
                    val deferred = CompletableDeferred<Boolean>()
                    pendingConfirmations[chatId] = deferred
                    deferred.await()
                }
            },
            notifyUser = { chatId, message ->
                poller.sendMessage(chatId, message)
            },
            screenAgent = screenAgent,
        ).also { runner ->
            // ── Official Skills (25 total) ──
            runner.register(SwigySkill())
            runner.register(ZomatoSkill())
            runner.register(ZeptoSkill())
            runner.register(BlinkitSkill())
            runner.register(YouTubeSkill())
            runner.register(InstagramSkill())
            runner.register(PhonePeSkill())
            runner.register(GPaySkill())
            runner.register(PaytmSkill())
            runner.register(CREDSkill())
            runner.register(MapsSkill())
            runner.register(OlaSkill())
            runner.register(UberSkill())
            runner.register(FlipkartSkill())
            runner.register(AmazonSkill())
            runner.register(WhatsAppSkill())
            runner.register(ChromeSkill())
            // ── Business & Productivity Skills ──
            runner.register(ScreenReaderSkill())
            runner.register(GmailSkill())
            runner.register(FileManagerSkill())
            runner.register(CalendarSkill())
            runner.register(NotesSkill())
            runner.register(SettingsSkill())
            runner.register(ContactsSkill())
            // ── General Agent (catch-all) ──
            runner.register(GeneralSkill())

            // Load community skills
            skillStore.loadAll().forEach { remote ->
                runner.register(RemoteSkill(remote))
            }
        }

        brain = AIBrain(
            apiKey = config.claudeApiKey,
            availableSkills = skillRunner.getSkillInfoForAI(),
            provider = config.aiProvider,
            model = config.aiModel,
        )

        poller = TelegramPoller(
            botToken = config.telegramBotToken,
            authorizedChatIds = config.authorizedChatIds,
            onMessage = ::handleMessage,
        )

        poller.start()
    }

    fun stop() { poller.stop() }

    private suspend fun handleMessage(msg: IncomingMessage): String {
        val trimmed = msg.text.trim()

        // ── Built-in commands ──
        when {
            trimmed.lowercase() == "/start" -> return buildWelcomeMessage()
            trimmed.lowercase() == "/skills" -> return buildSkillsMessage()
            trimmed.lowercase() == "/status" -> return buildStatusMessage()
            trimmed.lowercase() == "/history" -> return activityLog.buildHistoryMessage()
            trimmed.lowercase() == "/memory" -> return buildMemoryMessage()
            trimmed.lowercase() == "/forget" -> {
                userMemory.forgetAll()
                return "🧹 All learned preferences cleared. Starting fresh."
            }
            trimmed.lowercase() == "/knowledge" -> return appKnowledge.buildSummaryMessage()
            trimmed.lowercase() == "/knowledge clear" -> {
                appKnowledge.clearAll()
                return "🧹 App knowledge cleared. I'll re-learn as you use me."
            }
            trimmed.lowercase().startsWith("/knowledge clear ") -> {
                val pkg = trimmed.substringAfter("/knowledge clear ").trim()
                appKnowledge.clearApp(pkg)
                return "Cleared knowledge for $pkg."
            }
            trimmed.lowercase().startsWith("/forget ") -> {
                val idx = trimmed.substringAfter("/forget ").trim().toIntOrNull()
                return if (idx != null && userMemory.forget(idx)) {
                    "Preference #$idx removed."
                } else {
                    "Usage: /forget <number> — see /memory for the list"
                }
            }
            trimmed.lowercase() == "/clear" -> {
                brain.clearHistory(msg.chatId)
                return "Conversation memory cleared. Fresh start."
            }
            trimmed.lowercase() == "/mode" -> return toggleMode(msg.chatId)
            trimmed.lowercase().startsWith("/install ") -> {
                val url = trimmed.substringAfter("/install ").trim()
                return installSkill(url)
            }
            trimmed.lowercase().startsWith("/uninstall ") -> {
                val skillId = trimmed.substringAfter("/uninstall ").trim()
                return uninstallSkill(skillId)
            }
        }

        // ── Resolve pending confirmation ──
        pendingConfirmations.remove(msg.chatId)?.let { deferred ->
            val confirmed = trimmed.uppercase() in listOf("YES", "Y", "YEAH", "YEP", "OK", "SURE", "DO IT", "CONTINUE", "GO", "PROCEED")
            deferred.complete(confirmed)
            return if (confirmed) "Got it. Proceeding..." else "Cancelled."
        }

        // ── AI Learning: detect if user is teaching the agent ──
        // e.g. "next time search before tapping" or "remember I don't want auto-send"
        val preference = userMemory.extractPreference(trimmed)
        if (preference != null && userMemory.learningEnabled) {
            val saved = userMemory.remember(preference)
            if (saved) {
                return "📌 Got it! I'll remember:\n_\"$preference\"_\n\nThis will apply to future tasks. Use /memory to see all preferences, /forget to remove any."
            }
        }

        // ── Normal flow: AI -> Skill ──
        poller.sendTyping(msg.chatId)
        val plan = brain.process(msg.chatId, trimmed)

        val response = when (plan.type) {
            PlanType.DIRECT_REPLY -> {
                activityLog.log(trimmed, null, "success", plan.directReply?.take(100) ?: "")
                plan.directReply ?: "?"
            }

            PlanType.RUN_SKILL -> {
                executeSingleSkill(msg, trimmed, plan.skillId!!, plan.params)
            }

            PlanType.MULTI_STEP -> {
                executeMultiStep(msg, trimmed, plan.steps)
            }

            PlanType.UNKNOWN -> {
                activityLog.log(trimmed, null, "failure", "Unknown command")
                plan.directReply ?: "I didn't understand that. Try again."
            }
        }

        return response
    }

    private suspend fun executeSingleSkill(
        msg: IncomingMessage,
        userText: String,
        skillId: String,
        params: Map<String, Any>,
    ): String {
        val result = skillRunner.execute(
            skillId = skillId,
            params = params,
            chatId = msg.chatId,
            userId = msg.username ?: msg.chatId.toString(),
        )
        return when (result) {
            is SkillResult.Success -> {
                activityLog.log(userText, skillId, "success", result.message.take(100))
                sendWithScreenshot(msg.chatId, result.message)
            }
            is SkillResult.Failure -> {
                activityLog.log(userText, skillId, "failure", result.reason)
                "❌ ${result.reason}"
            }
            is SkillResult.NeedsConfirmation -> result.prompt
        }
    }

    private suspend fun executeMultiStep(
        msg: IncomingMessage,
        userText: String,
        steps: List<SkillStep>,
    ): String {
        val totalSteps = steps.size
        val results = mutableListOf<String>()

        poller.sendMessage(msg.chatId, "🔄 Running $totalSteps steps...")

        for ((index, step) in steps.withIndex()) {
            val stepNum = index + 1
            poller.sendMessage(msg.chatId, "⚡ Step $stepNum/$totalSteps: ${step.skillId}...")

            val result = skillRunner.execute(
                skillId = step.skillId,
                params = step.params,
                chatId = msg.chatId,
                userId = msg.username ?: msg.chatId.toString(),
            )

            when (result) {
                is SkillResult.Success -> {
                    activityLog.log("$userText [step $stepNum]", step.skillId, "success", result.message.take(100))
                    val stepMsg = "✅ Step $stepNum/${totalSteps}: ${result.message}"
                    sendWithScreenshot(msg.chatId, stepMsg)
                    results.add(stepMsg)
                }
                is SkillResult.Failure -> {
                    activityLog.log("$userText [step $stepNum]", step.skillId, "failure", result.reason)
                    results.add("❌ Step $stepNum (${step.skillId}): ${result.reason}")
                }
                is SkillResult.NeedsConfirmation -> {
                    results.add("⏸️ Step $stepNum (${step.skillId}): ${result.prompt}")
                    break
                }
            }
        }

        // Final summary (photos already sent per step)
        return if (results.all { it.startsWith("✅") }) "" // all good, photos sent
        else results.filter { !it.startsWith("✅") }.joinToString("\n\n") // only failures as text
    }

    // Take a screenshot and send as photo with caption.
    // If screenshot unavailable (API < 30 or service issue), returns the message as text.
    private suspend fun sendWithScreenshot(chatId: Long, message: String): String {
        val service = AgentAccessibilityService.instance
        if (service != null) {
            try {
                val bitmap = service.captureScreenshot()
                if (bitmap != null) {
                    poller.sendPhoto(chatId, bitmap, message)
                    return "" // already sent as photo — caller sends nothing
                }
            } catch (_: Exception) { /* fall through to text */ }
        }
        return message // no screenshot — return as plain text
    }

    private fun toggleMode(chatId: Long): String {
        val prefs = context.getSharedPreferences("bharatdroid", Context.MODE_PRIVATE)
        val current = prefs.getBoolean("ask_permission", true)
        val newMode = !current
        prefs.edit().putBoolean("ask_permission", newMode).apply()

        return if (newMode) {
            "Mode: *Ask Permission*\nI'll confirm before doing anything."
        } else {
            "Mode: *Just Do It*\nNo questions asked. I'll execute immediately."
        }
    }

    private fun buildMemoryMessage(): String {
        val memories = userMemory.getAll()
        if (memories.isEmpty()) {
            return "📭 No preferences saved yet.\n\nTell me something like:\n_\"Next time, always search before tapping\"_\nor\n_\"Remember I want messages typed, not auto-sent\"_\n\nI'll learn and follow your preferences automatically."
        }
        val list = memories.mapIndexed { i, m -> "${i + 1}. $m" }.joinToString("\n")
        val status = if (userMemory.learningEnabled) "✅ Learning: ON" else "❌ Learning: OFF (toggle in Settings)"
        return "*Learned Preferences:*\n\n$list\n\n$status\n\nRemove one: /forget 1\nRemove all: /forget"
    }

    private fun buildWelcomeMessage(): String = """
*BharatDroid Agent* is live on this phone.

Tell me what to do — English or Hindi. I'll do it.

*Food & Grocery:*
- "Biryani from Swiggy under 200"
- "Order milk from Blinkit"
- "Pizza from Zomato"

*Payments:*
- "Recharge 9876543210 for 239 on PhonePe"
- "Send 500 to mom@upi on GPay"

*Cabs:*
- "Book Ola to airport"
- "Uber to Connaught Place"

*Productivity:*
- "Read my latest email"
- "What's on my calendar today?"
- "Create a note: buy groceries"
- "Find contact Mom"
- "Read what's on screen"

*More:*
- "Navigate to Gateway of India"
- "Message mom on WhatsApp: coming home"
- "Play Alan Walker Faded on YouTube"
- "Earbuds under 1000 on Amazon"
- "Open calculator and compute 25 * 4"

*Commands:*
/skills — list all skills
/status — agent health check
/history — recent activity
/memory — see what I've learned about your preferences
/forget — clear learned preferences
/knowledge — see what I've learned about each app's layout
/mode — toggle Ask Permission / Just Do It
/clear — reset conversation memory
/install <url> — add community skill

💡 *Tips:*
- I learn from you! Say _"next time, do it like this..."_ and I'll remember.
- I also learn app layouts automatically — each task makes me faster on that app.
    """.trimIndent()

    private suspend fun installSkill(url: String): String {
        if (url.isBlank()) return "Usage: /install <url to skill JSON>"
        val result = skillStore.install(url)
        return result.fold(
            onSuccess = { manifest ->
                skillRunner.register(RemoteSkill(manifest))
                "Installed: *${manifest.name}* by `${manifest.author}`\n" +
                "Permissions: ${manifest.permissions.joinToString(", ")}"
            },
            onFailure = { e -> "Install failed: ${e.message}" }
        )
    }

    private fun uninstallSkill(skillId: String): String {
        return if (skillStore.uninstall(skillId)) {
            "Skill '$skillId' uninstalled. Restart agent to apply."
        } else {
            "No community skill '$skillId'.\nInstalled: ${skillStore.listInstalled().joinToString()}"
        }
    }

    private fun buildSkillsMessage(): String {
        val skills = skillRunner.listSkills()
        if (skills.isEmpty()) return "No skills loaded."

        val grouped = skills.groupBy { it.trusted }
        val sb = StringBuilder("*Available Skills (${skills.size}):*\n\n")

        grouped[true]?.let { official ->
            official.forEach { s ->
                sb.appendLine("*${s.name}* — ${s.description}")
            }
        }

        grouped[false]?.let { community ->
            sb.appendLine("\n*Community Skills:*")
            community.forEach { s ->
                sb.appendLine("${s.name} by ${s.author} — ${s.description}")
            }
        }

        return sb.toString().trimEnd()
    }

    private fun buildStatusMessage(): String {
        val serviceOk = AgentAccessibilityService.isConnected
        val count = activityLog.todayCount()
        val prefs = context.getSharedPreferences("bharatdroid", Context.MODE_PRIVATE)
        val mode = if (prefs.getBoolean("ask_permission", true)) "Ask Permission" else "Just Do It"
        val providerStr = prefs.getString("ai_provider", "GEMINI") ?: "GEMINI"
        val modelStr = prefs.getString("ai_model", "") ?: ""
        val knownApps = appKnowledge.getAppList().size
        val memCount = userMemory.getAll().size
        return """
*BharatDroid Status*

Agent: Running
Accessibility: ${if (serviceOk) "Connected" else "Not connected"}
Skills: ${skillRunner.listSkills().size} loaded
Mode: $mode
AI: $providerStr ${if (modelStr.isNotBlank()) "($modelStr)" else ""}
Today: $count commands processed
Apps learned: $knownApps  |  Preferences: $memCount
        """.trimIndent()
    }
}

data class AgentConfig(
    val telegramBotToken: String,
    val claudeApiKey: String,
    val authorizedChatIds: Set<Long>,
    val askPermission: Boolean = true,
    val aiProvider: AIProvider = AIProvider.GEMINI,
    val aiModel: String = "",
)
