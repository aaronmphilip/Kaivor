package com.bharatdroid.agent

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.bharatdroid.agent.skills.RemoteSkill
import com.bharatdroid.agent.skills.SkillResult
import com.bharatdroid.agent.skills.SkillStore
import com.bharatdroid.agent.skills.builtin.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex

class AgentOrchestrator(
    private val context: Context,
    private val config: AgentConfig,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val skillStore = SkillStore(context)
    private val activityLog = ActivityLog(context)
    private val userMemory = UserMemory(context)
    private val appKnowledge = AppKnowledgeBase(context)
    private val muteStore = MuteStore(context)

    // Wake lock: keeps the screen on while a skill is executing so the
    // accessibility service can interact with apps even when called from
    // a locked/sleeping phone. Released immediately after the task finishes.
    @Suppress("DEPRECATION")
    private val wakeLock: PowerManager.WakeLock by lazy {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE,
            "bharatdroid:agent_task"
        )
    }
    // Thread-safe: TelegramPoller fires scope.launch per message, so multiple
    // coroutines access this map concurrently. mutableMapOf is NOT thread-safe.
    private val pendingConfirmations = java.util.concurrent.ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()

    // Serializes skill execution — only one task runs at a time.
    // When a new message arrives, requestStop() is called first so the
    // running task exits quickly, then this lock is acquired for the new task.
    private val taskMutex = Mutex()

    private lateinit var poller: TelegramPoller
    private lateinit var brain: AIBrain
    private lateinit var skillRunner: SkillRunner
    private lateinit var screenAgent: ScreenAgent

    fun start() {
        // Create the AI-driven screen agent — inject user memory so learned preferences
        // are automatically applied to every executeGoal call, without touching skill files
        screenAgent = ScreenAgent(
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
            // ── Official Skills (31 total) ──
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
            // ── Composite / Multi-App Skills ──
            runner.register(TravelPlannerSkill())
            runner.register(PriceComparatorSkill())
            runner.register(FoodDealFinderSkill())
            runner.register(BillSplitterSkill())
            runner.register(MorningBriefSkill())
            runner.register(EmergencySOSSkill())
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

        // Wire the 24x7 notification relay. The listener service is declared in
        // the manifest; this just hands it the poller/mute store/chat id. If the
        // user hasn't granted notification-access yet, rebind() is a no-op.
        config.authorizedChatIds.firstOrNull()?.let { chatId ->
            NotificationRelay.attach(poller, muteStore, chatId)
            NotificationRelay.rebind(context)
        }
    }

    fun stop() { poller.stop() }

    private suspend fun handleMessage(msg: IncomingMessage): String {
        val trimmed = msg.text.trim()
        val lower = trimmed.lowercase()

        // ── Notification reply relay ─────────────────────────────────────────
        // User replied (using Telegram's Reply button) to a forwarded notification.
        // Try RemoteInput first (fastest — fires directly into the source app).
        // If that fails (no quick-reply action on the notification), fall back to
        // opening the app and sending via the accessibility skill.
        if (msg.replyToMessageId != null) {
            val delivered = NotificationRelay.sendReply(msg.replyToMessageId, trimmed)
            if (delivered) return "✉️ Reply sent."

            // RemoteInput unavailable — try skill-based fallback
            val rec = NotificationRelay.getRecord(msg.replyToMessageId)
            if (rec != null) {
                val contact = rec.lastTitle.takeIf { it.isNotBlank() } ?: "them"
                val skillId = when {
                    rec.pkg.contains("whatsapp") -> "whatsapp"
                    rec.pkg.contains("telegram") -> "whatsapp" // close enough
                    else -> null
                }
                if (skillId != null) {
                    val params = mapOf(
                        "action" to "send",
                        "contact" to contact,
                        "message" to trimmed,
                    )
                    poller.sendTyping(msg.chatId)
                    // Acquire wake lock for skill execution
                    if (!wakeLock.isHeld) wakeLock.acquire(120_000L)
                    return try {
                        screenAgent.clearStop()
                        val result = skillRunner.execute(skillId, params, msg.chatId,
                            msg.username ?: msg.chatId.toString())
                        when (result) {
                            is SkillResult.Success -> result.message
                            is SkillResult.Failure -> "❌ Could not reply: ${result.reason}"
                            else -> result.toString()
                        }
                    } finally {
                        try { if (wakeLock.isHeld) wakeLock.release() } catch (_: Exception) {}
                    }
                }
            }
            // No record or unknown app — fall through to normal AI routing
        }

        // ── AUTO-STOP: every new message kills the currently running task ─────
        // Exceptions:
        //   • Confirmation answers (YES/NO) — they continue a pending task, not start a new one
        //   • Explicit stop commands — handled below, requestStop() called there
        //
        // Why: TelegramPoller fires scope.launch per message, so multiple messages run in
        // parallel. Without this, "open Obsidian" while YouTube is running would fight with
        // the YouTube task on the same accessibility service, causing chaos.
        //
        // How: requestStop() cancels the in-flight OkHttp call (~100ms) + sets stop flag.
        // The running task sees the flag at its next step and exits cleanly.
        val hasPendingConfirmation = pendingConfirmations.containsKey(msg.chatId)
        val isStopCommand = run {
            val stopExactInner = setOf("stop", "रुको", "ruko", "band karo", "cancel", "bas", "बस",
                "rok", "रोक", "hatao", "हटाओ", "abort", "quit", "chhod do", "छोड़ दो",
                "mat karo", "मत करो", "nahi", "नहीं", "enough", "done stop")
            val stopContainsInner = listOf("stop", "रुको", "cancel", "abort", "band kar", "रोक", "bas kar")
            lower in stopExactInner || stopContainsInner.any { lower.contains(it) }
        }
        if (!hasPendingConfirmation && !isStopCommand) {
            // Kill whatever is currently running — the new message takes priority
            screenAgent.requestStop()
        }

        // ── STOP — explicit stop command ──────────────────────────────────────
        if (isStopCommand) {
            screenAgent.requestStop()
            return "⛔ Stopping current task. Left everything where it was."
        }

        // ── Built-in commands ──
        when {
            trimmed.lowercase() == "/start" -> return buildWelcomeMessage()
            trimmed.lowercase() == "/skills" -> return buildSkillsMessage()
            trimmed.lowercase() == "/status" -> return buildStatusMessage()
            trimmed.lowercase() == "/history" -> return activityLog.buildHistoryMessage()
            trimmed.lowercase() == "/memory" -> return buildMemoryMessage()
            trimmed.lowercase() == "/forget" -> {
                val count = userMemory.getAll().size
                userMemory.forgetAll()
                return "🧹 All $count rules cleared. Starting fresh."
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
                val arg = trimmed.substringAfter("/forget ").trim()
                // Support: /forget 3  OR  /forget 1,3,5  OR  /forget 2-5  OR  /forget 1,3-5,7
                val indices = userMemory.parseIndexString(arg)
                return when {
                    indices.isEmpty() -> "Usage: `/forget 3` or `/forget 1,3,5` or `/forget 2-5`\nSee /memory for the numbered list."
                    indices.size == 1 -> {
                        if (userMemory.forget(indices[0])) "✅ Rule #${indices[0]} removed."
                        else "Rule #${indices[0]} not found. Use /memory to see the list."
                    }
                    else -> {
                        val removed = userMemory.forgetMultiple(indices)
                        "✅ Removed $removed rule${if (removed != 1) "s" else ""} (${indices.joinToString(", ")}).\nUse /memory to see what's left."
                    }
                }
            }

            trimmed.lowercase().startsWith("/remember ") -> {
                // Manually add a rule directly without needing trigger phrases
                // e.g. /remember Always confirm before sending WhatsApp messages
                val rule = trimmed.substringAfter("/remember ").trim()
                return if (rule.isBlank()) {
                    "Usage: `/remember <your rule>`\nExample: `/remember Always confirm before sending WhatsApp messages`"
                } else if (!userMemory.learningEnabled) {
                    "⚠️ Learning is OFF. Turn it on in Settings first."
                } else {
                    val saved = userMemory.addRule(rule)
                    if (saved) "📌 Rule saved: _\"$rule\"_\n\nUse /memory to see all rules."
                    else "Already have that rule saved."
                }
            }
            trimmed.lowercase() == "/muted" -> return buildMutedListMessage()
            trimmed.lowercase().startsWith("/mute ") -> {
                val arg = trimmed.substringAfter("/mute ").trim()
                val pkg = resolvePackage(arg) ?: return "Can't find app \"$arg\". Try the package name (e.g. `com.whatsapp`) or the exact app label."
                muteStore.mute(pkg)
                return "🔕 Muted ${NotificationRelay.labelFor(context, pkg)} (`$pkg`). Use `/unmute $arg` to re-enable."
            }
            trimmed.lowercase().startsWith("/unmute ") -> {
                val arg = trimmed.substringAfter("/unmute ").trim()
                val pkg = resolvePackage(arg) ?: return "Can't find app \"$arg\"."
                muteStore.unmute(pkg)
                return "🔔 Unmuted ${NotificationRelay.labelFor(context, pkg)} (`$pkg`)."
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
        // Brain routing is fast (just an API call), so do it outside the lock.
        // Only the actual on-device skill execution is locked — one task at a time.
        poller.sendTyping(msg.chatId)
        val plan = brain.process(msg.chatId, trimmed)

        // Direct replies and unknowns don't touch the phone — no lock needed.
        if (plan.type == PlanType.DIRECT_REPLY) {
            activityLog.log(trimmed, null, "success", plan.directReply?.take(100) ?: "")
            return plan.directReply ?: "?"
        }
        if (plan.type == PlanType.UNKNOWN) {
            activityLog.log(trimmed, null, "failure", "Unknown command")
            return plan.directReply ?: "I didn't understand that. Try again."
        }

        // Skill execution touches the phone — serialize with mutex.
        // requestStop() was already called at the top of handleMessage, so the
        // previous task is stopping. We wait here until the lock is released,
        // then start the new task with a clean slate.
        //
        // Timeout: if the old task doesn't stop within 15s (e.g. skill stuck in
        // a blocking call that ignores stopRequested), give up waiting and proceed.
        // This prevents permanent deadlock from misbehaving skills.
        val acquired = kotlinx.coroutines.withTimeoutOrNull(15_000L) {
            taskMutex.lock()
        }
        return try {
            // At this point the previous task has stopped and released the mutex.
            // Clear any leftover stop flag so the NEW task doesn't immediately self-stop.
            // Root cause of the "Stopped." spam: requestStop() is called for every message
            // to kill old tasks, but with no old task running the flag stays true and the
            // new task sees it in executeGoal's first check and returns "⛔ Stopped." before
            // doing anything. Clearing here (after acquiring the mutex = old task is done)
            // fixes this without losing the ability to stop a genuinely running task.
            screenAgent.clearStop()

            // Wake screen so agent can interact with apps even if phone was sleeping.
            if (!wakeLock.isHeld) wakeLock.acquire(180_000L) // 3 min max; released in finally

            // Dismiss lock screen — swipe up from bottom (swipe-only lock) or navigate home
            // to land on the actual app. No-op if screen is already unlocked.
            AgentAccessibilityService.instance?.let { svc ->
                val km = context.getSystemService(android.app.KeyguardManager::class.java)
                if (km?.isKeyguardLocked == true) {
                    kotlinx.coroutines.delay(300) // let screen fully wake
                    svc.swipeUpToUnlock()
                    kotlinx.coroutines.delay(400) // let lock screen animate away
                }
            }

            when (plan.type) {
                PlanType.RUN_SKILL -> executeSingleSkill(msg, trimmed, plan.skillId!!, plan.params)
                PlanType.MULTI_STEP -> executeMultiStep(msg, trimmed, plan.steps)
                else -> "?"
            }
        } finally {
            try { if (wakeLock.isHeld) wakeLock.release() } catch (_: Exception) {}
            if (acquired != null) taskMutex.unlock()
        }
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
                    break // don't continue — later steps likely depend on this one
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

    /**
     * Resolve "WhatsApp" or "com.whatsapp" to a package name. If the input already
     * looks like a package id (contains a dot), we trust it. Otherwise we scan
     * installed apps for a case-insensitive label match.
     */
    private fun resolvePackage(arg: String): String? {
        if (arg.isBlank()) return null
        if (arg.contains(".")) return arg
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = try { pm.queryIntentActivities(intent, 0) } catch (_: Exception) { return null }
        val match = apps.firstOrNull { info ->
            val label = info.loadLabel(pm).toString()
            label.equals(arg, ignoreCase = true)
        } ?: apps.firstOrNull { info ->
            val label = info.loadLabel(pm).toString()
            label.contains(arg, ignoreCase = true)
        }
        return match?.activityInfo?.packageName
    }

    private fun buildMutedListMessage(): String {
        val muted = muteStore.list()
        val granted = NotificationRelay.isPermissionGranted(context)
        val header = if (granted) "✅ Notification relay: ON" else "⚠️ Notification relay needs permission. Settings → Notifications → Device & app notifications → BharatDroid → Allow."
        if (muted.isEmpty()) {
            return "$header\n\n🔔 No apps muted. All notifications are forwarded.\n\n*Commands:*\n`/mute WhatsApp` or `/mute com.whatsapp`\n`/unmute WhatsApp`"
        }
        val list = muted.mapIndexed { i, pkg ->
            "${i + 1}. ${NotificationRelay.labelFor(context, pkg)} (`$pkg`)"
        }.joinToString("\n")
        return "$header\n\n🔕 *Muted apps (${muted.size}):*\n$list\n\nUse `/unmute <name>` to re-enable."
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
        val status = if (userMemory.learningEnabled) "✅ Learning: ON" else "❌ Learning: OFF (toggle in Settings)"

        if (memories.isEmpty()) {
            return buildString {
                appendLine("📭 No rules saved yet.")
                appendLine()
                appendLine("*Two ways to add rules:*")
                appendLine()
                appendLine("1️⃣ *Just tell me naturally:*")
                appendLine("_\"Next time always confirm before sending\"_")
                appendLine("_\"Remember I want messages typed, not auto-sent\"_")
                appendLine("_\"Always sort Amazon by rating\"_")
                appendLine()
                appendLine("2️⃣ *Direct command:*")
                appendLine("`/remember Always confirm before WhatsApp send`")
                appendLine()
                append(status)
            }
        }

        val list = memories.mapIndexed { i, m -> "${i + 1}. $m" }.joinToString("\n")
        return buildString {
            appendLine("📌 *Your Rules* (${memories.size}/30):")
            appendLine()
            appendLine(list)
            appendLine()
            appendLine(status)
            appendLine()
            appendLine("*Manage rules:*")
            appendLine("`/forget 3` — remove rule 3")
            appendLine("`/forget 1,3,5` — remove rules 1, 3 and 5")
            appendLine("`/forget 2-5` — remove rules 2 through 5")
            appendLine("`/forget` — clear all rules")
            appendLine("`/remember <rule>` — add a rule directly")
        }.trimEnd()
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
/memory — see your saved rules
/remember <rule> — add a rule directly
/forget 3 — delete rule #3
/forget 1,3,5 — delete rules 1, 3 and 5
/forget 2-5 — delete rules 2 through 5
/forget — clear all rules
/knowledge — see what I've learned per app
/muted — notification relay status + muted apps
/mute <app> — stop forwarding notifications from that app
/unmute <app> — re-enable notifications from that app
/mode — toggle Ask Permission / Just Do It
/clear — reset conversation memory
/install <url> — add community skill

💡 *Tips:*
- Teach me naturally: _"next time always confirm before sending"_
- Or add a rule directly: `/remember Always sort Amazon results by rating`
- Rules I learn are *mandatory* — I follow them every time, not just sometimes.
- I also learn each app's layout — every task makes me faster.
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
        val notifGranted = NotificationRelay.isPermissionGranted(context)
        val mutedCount = muteStore.list().size
        return """
*BharatDroid Status*

Agent: Running
Accessibility: ${if (serviceOk) "✅ Connected" else "❌ Not connected"}
Notification relay: ${if (notifGranted) "✅ Active${if (mutedCount > 0) " ($mutedCount muted)" else ""}" else "⚠️ Off — grant in Settings → Notification Access"}
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
