package com.bharatdroid.agent

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import com.bharatdroid.agent.skills.DeliveryMode
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
    private val conversationContext = ConversationContextStore(context)
    private val savedPlaces = SavedPlacesStore(context)
    private val quickMacros = QuickMacrosStore(context)
    private val permissionsStore = PermissionsStore(context)
    private val scheduleStore = ScheduleStore(context)
    private val routineStore = RoutineStore(context)
    private var lastRawCommand: String = ""  // for "again"/"repeat" feature

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

    // Serializes skill execution â€” only one task runs at a time.
    // When a new message arrives, requestStop() is called first so the
    // running task exits quickly, then this lock is acquired for the new task.
    private val taskMutex = Mutex()

    private lateinit var poller: TelegramPoller
    private lateinit var actionBrain: AIBrain
    private lateinit var knowledgeBrain: KnowledgeBrain
    private lateinit var documentBrain: DocumentSummaryBrain
    private lateinit var skillRunner: SkillRunner
    private lateinit var screenAgent: ScreenAgent

    fun start() {
        // Create the AI-driven screen agent â€” inject user memory so learned preferences
        // are automatically applied to every executeGoal call, without touching skill files
        screenAgent = ScreenAgent(
            apiKey = config.agentApiKey,
            provider = config.agentProvider,
            model = config.agentModel,
            userMemory = userMemory,
            appKnowledge = appKnowledge,
        )

        skillRunner = SkillRunner(
            context = context,
            permissionsStore = permissionsStore,
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
            // â”€â”€ Official Skills â”€â”€
            runner.register(SwigySkill())
            runner.register(ZomatoSearchFirstSkill())
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
            // â”€â”€ Business & Productivity Skills â”€â”€
            runner.register(ScreenReaderSkill())
            runner.register(ReadingConciergeSkill())
            runner.register(GmailSkill())
            runner.register(FileManagerSkill())
            runner.register(CalendarSkill())
            runner.register(NotesSkill())
            runner.register(SettingsSkill())
            runner.register(ContactsSkill())
            // â”€â”€ Composite / Multi-App Skills â”€â”€
            runner.register(TravelPlannerSkill())
            runner.register(RideConciergeSkill())
            runner.register(PriceComparatorSkill())
            runner.register(FoodDealFinderSkill())
            runner.register(BillSplitterSkill())
            runner.register(MorningBriefSkill())
            runner.register(EmergencySOSSkill())
            // â”€â”€ General Agent (catch-all) â”€â”€
            runner.register(GeneralSkill())

            // Load community skills
            skillStore.loadAll().forEach { remote ->
                runner.register(RemoteSkill(remote))
            }
        }

        actionBrain = AIBrain(
            apiKey = config.agentApiKey,
            availableSkills = skillRunner.getSkillInfoForAI(),
            provider = config.agentProvider,
            model = config.agentModel,
        )
        knowledgeBrain = KnowledgeBrain(
            apiKey = config.researchApiKey,
            provider = config.researchProvider,
            model = config.researchModel,
        )
        documentBrain = DocumentSummaryBrain(
            context = context,
            apiKey = config.agentApiKey,
            provider = config.agentProvider,
            model = config.agentModel,
        )

        poller = TelegramPoller(
            botToken = config.telegramBotToken,
            authorizedChatIds = config.authorizedChatIds,
            downloadDir = java.io.File(context.cacheDir, "telegram"),
            commands = buildTelegramCommands(),
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
        val trimmed = normalizeIncomingTelegramText(msg.text).trim()
        val lower = trimmed.lowercase()
        val command = parseTelegramCommand(trimmed)

        // Research commands should never fall through to agentic phone actions or Chrome.
        extractResearchCommand(trimmed)?.let { researchCommand ->
            return handleInfoCommand(msg.chatId, researchCommand.query, researchCommand.depth)
        }
        when (command?.name) {
            "info" -> return handleInfoCommand(msg.chatId, command.args, ResearchDepth.QUICK)
            "research" -> return handleInfoCommand(msg.chatId, command.args, ResearchDepth.DEEP)
        }

        // â”€â”€ Notification reply relay â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        // User replied (using Telegram's Reply button) to a forwarded notification.
        // Try RemoteInput first (fastest â€” fires directly into the source app).
        // If that fails (no quick-reply action on the notification), fall back to
        // opening the app and sending via the accessibility skill.
        if (msg.replyToMessageId != null) {
            val delivered = NotificationRelay.sendReply(msg.replyToMessageId, trimmed)
            if (delivered) return "âœ‰ï¸ Reply sent."

            // RemoteInput unavailable â€” try skill-based fallback
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
                            is SkillResult.Failure -> "âŒ Could not reply: ${result.reason}"
                            else -> result.toString()
                        }
                    } finally {
                        try { if (wakeLock.isHeld) wakeLock.release() } catch (_: Exception) {}
                    }
                }
            }
            // No record or unknown app â€” fall through to normal AI routing
        }

        if (msg.attachment != null && msg.attachment.mimeType?.startsWith("audio") == true) {
            return handleVoiceNote(msg)
        }

        if (msg.attachment != null && (command == null || command.name == "summarize")) {
            return handleAttachmentMessage(msg, command?.args.orEmpty())
        }
        if (command?.name == "summarize") {
            return buildDocumentSummaryUsage()
        }

        // â”€â”€ AUTO-STOP: every new message kills the currently running task â”€â”€â”€â”€â”€
        // Exceptions:
        //   â€¢ Confirmation answers (YES/NO) â€” they continue a pending task, not start a new one
        //   â€¢ Explicit stop commands â€” handled below, requestStop() called there
        //
        // Why: TelegramPoller fires scope.launch per message, so multiple messages run in
        // parallel. Without this, "open Obsidian" while YouTube is running would fight with
        // the YouTube task on the same accessibility service, causing chaos.
        //
        // How: requestStop() cancels the in-flight OkHttp call (~100ms) + sets stop flag.
        // The running task sees the flag at its next step and exits cleanly.
        val hasPendingConfirmation = pendingConfirmations.containsKey(msg.chatId)
        val isStopCommand = run {
            val stopExactInner = setOf("stop", "à¤°à¥à¤•à¥‹", "ruko", "band karo", "cancel", "bas", "à¤¬à¤¸",
                "rok", "à¤°à¥‹à¤•", "hatao", "à¤¹à¤Ÿà¤¾à¤“", "abort", "quit", "chhod do", "à¤›à¥‹à¤¡à¤¼ à¤¦à¥‹",
                "mat karo", "à¤®à¤¤ à¤•à¤°à¥‹", "nahi", "à¤¨à¤¹à¥€à¤‚", "enough", "done stop")
            val stopContainsInner = listOf("stop", "à¤°à¥à¤•à¥‹", "cancel", "abort", "band kar", "à¤°à¥‹à¤•", "bas kar")
            lower in stopExactInner || stopContainsInner.any { lower.contains(it) }
        }
        if (!hasPendingConfirmation && !isStopCommand) {
            // Kill whatever is currently running â€” the new message takes priority
            screenAgent.requestStop()
        }

        // â”€â”€ STOP â€” explicit stop command â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        if (isStopCommand) {
            screenAgent.requestStop()
            return "â›” Stopping current task. Left everything where it was."
        }

        // â”€â”€ Built-in commands â”€â”€
        command?.let { parsed ->
            when (parsed.name) {
                "start", "help" -> return buildWelcomeMessage()
                "skills" -> return buildSkillsMessage()
                "status" -> return buildStatusMessage()
                "history" -> return activityLog.buildHistoryMessage()
                "memory" -> return buildMemoryMessage()
                "forget" -> {
                    if (parsed.args.isBlank()) {
                        val count = userMemory.getAll().size
                        userMemory.forgetAll()
                        return "ðŸ§¹ All $count rules cleared. Starting fresh."
                    }
                    val indices = userMemory.parseIndexString(parsed.args)
                    return when {
                        indices.isEmpty() -> "Usage: `/forget 3` or `/forget 1,3,5` or `/forget 2-5`\nSee /memory for the numbered list."
                        indices.size == 1 -> {
                            if (userMemory.forget(indices[0])) "âœ… Rule #${indices[0]} removed."
                            else "Rule #${indices[0]} not found. Use /memory to see the list."
                        }
                        else -> {
                            val removed = userMemory.forgetMultiple(indices)
                            "âœ… Removed $removed rule${if (removed != 1) "s" else ""} (${indices.joinToString(", ")}).\nUse /memory to see what's left."
                        }
                    }
                }
                "knowledge" -> {
                    if (parsed.args.isBlank()) {
                        return appKnowledge.buildSummaryMessage()
                    }
                    if (parsed.args.equals("clear", ignoreCase = true)) {
                        appKnowledge.clearAll()
                        return "ðŸ§¹ App knowledge cleared. I'll re-learn as you use me."
                    }
                    if (parsed.args.startsWith("clear ", ignoreCase = true)) {
                        val pkg = parsed.args.substringAfter("clear ").trim()
                        if (pkg.isBlank()) {
                            return "Usage: `/knowledge clear <package>`"
                        }
                        appKnowledge.clearApp(pkg)
                        return "Cleared knowledge for $pkg."
                    }
                }
                "knowledge_clear" -> {
                    if (parsed.args.isBlank()) {
                        appKnowledge.clearAll()
                        return "ðŸ§¹ App knowledge cleared. I'll re-learn as you use me."
                    }
                    appKnowledge.clearApp(parsed.args)
                    return "Cleared knowledge for ${parsed.args}."
                }
                "remember" -> {
                    if (parsed.args.isBlank()) {
                        return "Usage: `/remember <your rule>`\nExample: `/remember Always confirm before sending WhatsApp messages`"
                    }
                    return if (!userMemory.learningEnabled) {
                        "âš ï¸ Learning is OFF. Turn it on in Settings first."
                    } else {
                        val saved = userMemory.addRule(parsed.args)
                        if (saved) "ðŸ“Œ Rule saved: _\"${parsed.args}\"_\n\nUse /memory to see all rules."
                        else "Already have that rule saved."
                    }
                }
                "muted" -> return buildMutedListMessage()
                "mute" -> {
                    if (parsed.args.isBlank()) {
                        return "Usage: `/mute <app>`"
                    }
                    val pkg = resolvePackage(parsed.args)
                        ?: return "Can't find app \"${parsed.args}\". Try the package name (e.g. `com.whatsapp`) or the exact app label."
                    muteStore.mute(pkg)
                    return "ðŸ”• Muted ${NotificationRelay.labelFor(context, pkg)} (`$pkg`). Use `/unmute ${parsed.args}` to re-enable."
                }
                "unmute" -> {
                    if (parsed.args.isBlank()) {
                        return "Usage: `/unmute <app>`"
                    }
                    val pkg = resolvePackage(parsed.args) ?: return "Can't find app \"${parsed.args}\"."
                    muteStore.unmute(pkg)
                    return "ðŸ”” Unmuted ${NotificationRelay.labelFor(context, pkg)} (`$pkg`)."
                }
                "clear" -> {
                    actionBrain.clearHistory(msg.chatId)
                    knowledgeBrain.clearHistory(msg.chatId)
                    conversationContext.clear(msg.chatId)
                    return "Conversation memory cleared. Fresh start."
                }
                "place" -> return savedPlaces.handleCommand(parsed.args)
                "shortcut" -> return quickMacros.handleCommand(parsed.args)
                "permissions" -> return permissionsStore.handleCommand(parsed.args)
                "schedule" -> return scheduleStore.handleCommand(parsed.args, msg.chatId)
                "routine" -> {
                    when (val cmd = routineStore.handleCommand(parsed.args)) {
                        is RoutineStore.RoutineCommand.Reply -> return cmd.message
                        is RoutineStore.RoutineCommand.Run -> return runRoutine(cmd.routine, msg)
                    }
                }
                "mode" -> return permissionsStore.handleCommand(parsed.args)  // keep /mode working
                "install" -> return installSkill(parsed.args)
                "uninstall" -> return uninstallSkill(parsed.args)
                "screenshot" -> {
                    val bitmap = try {
                        AgentAccessibilityService.instance?.captureScreenshot()
                    } catch (_: Exception) { null }
                    return if (bitmap != null) {
                        poller.sendPhoto(msg.chatId, bitmap, "📸 Screenshot")
                        ""
                    } else {
                        "📸 Screenshot unavailable — make sure Accessibility Service is enabled."
                    }
                }
                "open" -> {
                    if (parsed.args.isBlank()) return "Usage: `/open <app name>`\nExamples: `/open Swiggy`, `/open Chrome`, `/open Settings`"
                    val pkg = resolvePackage(parsed.args)
                        ?: return "❌ Can't find \"${parsed.args}\". Try the exact app name or package (e.g. `com.swiggy.android`)."
                    return try {
                        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                            ?: return "❌ \"${parsed.args}\" is installed but can't be launched."
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        "📱 Opened *${parsed.args}*."
                    } catch (e: Exception) {
                        "❌ Could not open \"${parsed.args}\": ${e.message}"
                    }
                }
            }
        }

        when {
            trimmed.lowercase() == "/start" -> return buildWelcomeMessage()
            trimmed.lowercase() == "/skills" -> return buildSkillsMessage()
            trimmed.lowercase() == "/status" -> return buildStatusMessage()
            trimmed.lowercase() == "/history" -> return activityLog.buildHistoryMessage()
            trimmed.lowercase() == "/memory" -> return buildMemoryMessage()
            trimmed.lowercase() == "/forget" -> {
                val count = userMemory.getAll().size
                userMemory.forgetAll()
                return "ðŸ§¹ All $count rules cleared. Starting fresh."
            }
            trimmed.lowercase() == "/knowledge" -> return appKnowledge.buildSummaryMessage()
            trimmed.lowercase() == "/knowledge clear" -> {
                appKnowledge.clearAll()
                return "ðŸ§¹ App knowledge cleared. I'll re-learn as you use me."
            }
            trimmed.lowercase() == "/info" || trimmed.lowercase() == "/research" -> {
                return "Use `/info <topic>` for a quick web answer.\nUse `/research <topic>` for a deeper web search.\n\nExamples:\n`/info Who is Nikhil Kamath?`\n`/research Nvidia latest business update`"
            }
            trimmed.lowercase().startsWith("/info ") -> {
                return handleInfoCommand(msg.chatId, trimmed.substringAfter("/info ").trim(), ResearchDepth.QUICK)
            }
            trimmed.lowercase().startsWith("/research ") -> {
                return handleInfoCommand(msg.chatId, trimmed.substringAfter("/research ").trim(), ResearchDepth.DEEP)
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
                        if (userMemory.forget(indices[0])) "âœ… Rule #${indices[0]} removed."
                        else "Rule #${indices[0]} not found. Use /memory to see the list."
                    }
                    else -> {
                        val removed = userMemory.forgetMultiple(indices)
                        "âœ… Removed $removed rule${if (removed != 1) "s" else ""} (${indices.joinToString(", ")}).\nUse /memory to see what's left."
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
                    "âš ï¸ Learning is OFF. Turn it on in Settings first."
                } else {
                    val saved = userMemory.addRule(rule)
                    if (saved) "ðŸ“Œ Rule saved: _\"$rule\"_\n\nUse /memory to see all rules."
                    else "Already have that rule saved."
                }
            }
            trimmed.lowercase() == "/muted" -> return buildMutedListMessage()
            trimmed.lowercase().startsWith("/mute ") -> {
                val arg = trimmed.substringAfter("/mute ").trim()
                val pkg = resolvePackage(arg) ?: return "Can't find app \"$arg\". Try the package name (e.g. `com.whatsapp`) or the exact app label."
                muteStore.mute(pkg)
                return "ðŸ”• Muted ${NotificationRelay.labelFor(context, pkg)} (`$pkg`). Use `/unmute $arg` to re-enable."
            }
            trimmed.lowercase().startsWith("/unmute ") -> {
                val arg = trimmed.substringAfter("/unmute ").trim()
                val pkg = resolvePackage(arg) ?: return "Can't find app \"$arg\"."
                muteStore.unmute(pkg)
                return "ðŸ”” Unmuted ${NotificationRelay.labelFor(context, pkg)} (`$pkg`)."
            }
            trimmed.lowercase() == "/clear" -> {
                actionBrain.clearHistory(msg.chatId)
                knowledgeBrain.clearHistory(msg.chatId)
                conversationContext.clear(msg.chatId)
                return "Conversation memory cleared. Fresh start."
            }
            trimmed.lowercase() == "/mode" -> return permissionsStore.handleCommand("")
            trimmed.lowercase() == "/permissions" -> return permissionsStore.handleCommand("")
            trimmed.lowercase().startsWith("/permissions ") -> {
                return permissionsStore.handleCommand(trimmed.substringAfter("/permissions ").trim())
            }
            trimmed.lowercase() == "/place" -> return savedPlaces.handleCommand("")
            trimmed.lowercase().startsWith("/place ") -> {
                return savedPlaces.handleCommand(trimmed.substringAfter("/place ").trim())
            }
            trimmed.lowercase() == "/shortcut" -> return quickMacros.handleCommand("")
            trimmed.lowercase().startsWith("/shortcut ") -> {
                return quickMacros.handleCommand(trimmed.substringAfter("/shortcut ").trim())
            }
            trimmed.lowercase() == "/schedule" -> return scheduleStore.handleCommand("", msg.chatId)
            trimmed.lowercase().startsWith("/schedule ") -> {
                return scheduleStore.handleCommand(trimmed.substringAfter("/schedule ").trim(), msg.chatId)
            }
            trimmed.lowercase() == "/routine" -> return routineStore.buildListMessage()
            trimmed.lowercase().startsWith("/routine ") -> {
                when (val cmd = routineStore.handleCommand(trimmed.substringAfter("/routine ").trim())) {
                    is RoutineStore.RoutineCommand.Reply -> return cmd.message
                    is RoutineStore.RoutineCommand.Run -> return runRoutine(cmd.routine, msg)
                }
            }
            trimmed.lowercase().startsWith("/install ") -> {
                val url = trimmed.substringAfter("/install ").trim()
                return installSkill(url)
            }
            trimmed.lowercase().startsWith("/uninstall ") -> {
                val skillId = trimmed.substringAfter("/uninstall ").trim()
                return uninstallSkill(skillId)
            }
            trimmed.lowercase() == "/screenshot" -> {
                val bitmap = try {
                    AgentAccessibilityService.instance?.captureScreenshot()
                } catch (_: Exception) { null }
                return if (bitmap != null) {
                    poller.sendPhoto(msg.chatId, bitmap, "📸 Screenshot")
                    ""
                } else {
                    "📸 Screenshot unavailable — make sure Accessibility Service is enabled."
                }
            }
            trimmed.lowercase().startsWith("/open ") -> {
                val arg = trimmed.substringAfter("/open ").trim()
                if (arg.isBlank()) return "Usage: `/open <app name>`\nExamples: `/open Swiggy`, `/open Chrome`, `/open Settings`"
                val pkg = resolvePackage(arg)
                    ?: return "❌ Can't find \"$arg\". Try the exact app name or package (e.g. `com.swiggy.android`)."
                return try {
                    val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                        ?: return "❌ \"$arg\" is installed but can't be launched."
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    "📱 Opened *$arg*."
                } catch (e: Exception) {
                    "❌ Could not open \"$arg\": ${e.message}"
                }
            }
        }

        // â”€â”€ Resolve pending confirmation â”€â”€
        pendingConfirmations.remove(msg.chatId)?.let { deferred ->
            val confirmed = trimmed.uppercase() in listOf("YES", "Y", "YEAH", "YEP", "OK", "SURE", "DO IT", "CONTINUE", "GO", "PROCEED")
            deferred.complete(confirmed)
            return if (confirmed) "Got it. Proceeding..." else "Cancelled."
        }

        // â”€â”€ AI Learning: detect if user is teaching the agent â”€â”€
        // e.g. "next time search before tapping" or "remember I don't want auto-send"
        val preference = userMemory.extractPreference(trimmed)
        if (preference != null && userMemory.learningEnabled) {
            val saved = userMemory.remember(preference)
            if (saved) {
                return "ðŸ“Œ Got it! I'll remember:\n_\"$preference\"_\n\nThis will apply to future tasks. Use /memory to see all preferences, /forget to remove any."
            }
        }

        // ── "Again" / "Repeat" — re-run the last command ────────────────────
        val isAgainCommand = lower in setOf(
            "again", "repeat", "do it again", "redo", "same", "once more",
            "फिर से", "dobara", "phir se", "wahi karo", "ek baar aur",
        )
        if (isAgainCommand) {
            val lastCmd = lastRawCommand
            if (lastCmd.isBlank()) return "Nothing to repeat — I haven't run any command yet this session."
            poller.sendMessage(msg.chatId, "🔁 Repeating: _" + lastCmd.take(80) + "_")
            return handleMessage(msg.copy(text = lastCmd))
        }

        // ── Quick Macro resolution — check user-defined shortcuts first ────────
        val macroResolved = quickMacros.resolve(lower)
        if (macroResolved != null) {
            poller.sendMessage(msg.chatId, "⚡ Running shortcut: _" + macroResolved.take(80) + "_")
            return handleMessage(msg.copy(text = macroResolved))
        }

        // ── Saved Places expansion — resolve "home", "work" etc. in command ────
        val expandedCommand = savedPlaces.expandInCommand(trimmed)

        // ── Compound command: "X and Y" → run both sequentially ───────────────
        val andParts = splitCompoundCommand(expandedCommand)
        if (andParts != null) {
            lastRawCommand = trimmed
            poller.sendMessage(msg.chatId, "🔄 Running compound command in two steps...")
            val result1 = handleMessage(msg.copy(text = andParts.first))
            val result2 = handleMessage(msg.copy(text = andParts.second))
            return "1️⃣ *" + andParts.first.take(55) + "*
" + result1 + "

2️⃣ *" + andParts.second.take(55) + "*
" + result2
        }

        // Save raw command for "again" feature (never save meta-commands)
        lastRawCommand = trimmed

        // ── Normal flow: AI -> Skill ──
        // Brain routing is fast (just an API call), so do it outside the lock.
        // Only the actual on-device skill execution is locked — one task at a time.
        poller.sendTyping(msg.chatId)
        val route = BrainRoute(mode = BrainMode.ACTION, actionPrompt = expandedCommand)

        if (route.mode == BrainMode.DIRECT_REPLY) {
            activityLog.log(trimmed, null, "success", route.reply.take(100))
            return route.reply.ifBlank { "Okay." }
        }

        if (route.mode == BrainMode.KNOWLEDGE) {
            val knowledgeReply = knowledgeBrain.answer(
                chatId = msg.chatId,
                userMessage = route.knowledgeQuery.ifBlank { trimmed },
                contextHint = conversationContext.buildKnowledgeContext(msg.chatId),
            )
            conversationContext.rememberKnowledge(
                chatId = msg.chatId,
                query = knowledgeReply.query,
                topic = knowledgeReply.topic,
                summary = knowledgeReply.summary,
                sources = knowledgeReply.sources.map { it.url },
            )
            activityLog.log(trimmed, "knowledge", "success", knowledgeReply.summary.take(100))
            return knowledgeReply.reply
        }

        var hybridKnowledgeReply: KnowledgeReply? = null
        val actionPrompt = when (route.mode) {
            BrainMode.HYBRID -> {
                hybridKnowledgeReply = knowledgeBrain.answer(
                    chatId = msg.chatId,
                    userMessage = route.knowledgeQuery.ifBlank { trimmed },
                    contextHint = conversationContext.buildKnowledgeContext(msg.chatId),
                )
                conversationContext.rememberKnowledge(
                    chatId = msg.chatId,
                    query = hybridKnowledgeReply!!.query,
                    topic = hybridKnowledgeReply!!.topic,
                    summary = hybridKnowledgeReply!!.summary,
                    sources = hybridKnowledgeReply!!.sources.map { it.url },
                )
                route.actionPrompt.ifBlank { trimmed }
            }

            else -> route.actionPrompt.ifBlank { trimmed }
        }

        val actionContext = conversationContext.buildActionContext(msg.chatId)

        val plan = actionBrain.process(
            chatId = msg.chatId,
            userMessage = actionPrompt,
            contextHint = actionContext,
        )

        // Direct replies and unknowns don't touch the phone â€” no lock needed.
        if (plan.type == PlanType.DIRECT_REPLY) {
            val directReply = plan.directReply ?: "?"
            conversationContext.rememberAction(msg.chatId, actionPrompt, directReply)
            activityLog.log(trimmed, null, "success", directReply.take(100))
            return if (hybridKnowledgeReply != null) buildHybridReply(hybridKnowledgeReply!!, directReply) else directReply
        }
        if (plan.type == PlanType.UNKNOWN) {
            activityLog.log(trimmed, null, "failure", "Unknown command")
            return plan.directReply ?: "I didn't understand that. Try again."
        }

        // Skill execution touches the phone â€” serialize with mutex.
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
            // new task sees it in executeGoal's first check and returns "â›” Stopped." before
            // doing anything. Clearing here (after acquiring the mutex = old task is done)
            // fixes this without losing the ability to stop a genuinely running task.
            screenAgent.clearStop()

            // Wake screen so agent can interact with apps even if phone was sleeping.
            if (!wakeLock.isHeld) wakeLock.acquire(180_000L) // 3 min max; released in finally

            // Dismiss lock screen â€” swipe up from bottom (swipe-only lock) or navigate home
            // to land on the actual app. No-op if screen is already unlocked.
            AgentAccessibilityService.instance?.let { svc ->
                val km = context.getSystemService(android.app.KeyguardManager::class.java)
                if (km?.isKeyguardLocked == true) {
                    kotlinx.coroutines.delay(300) // let screen fully wake
                    svc.swipeUpToUnlock()
                    kotlinx.coroutines.delay(400) // let lock screen animate away
                }
            }

            val actionOutcome = when (plan.type) {
                PlanType.RUN_SKILL -> executeSingleSkill(msg, trimmed, plan.skillId!!, plan.params)
                PlanType.MULTI_STEP -> executeMultiStep(msg, trimmed, plan.steps)
                else -> ActionExecutionOutcome(reply = "?", contextSummary = "?")
            }
            conversationContext.rememberAction(msg.chatId, actionPrompt, actionOutcome.contextSummary)
            if (hybridKnowledgeReply != null) {
                buildHybridReply(hybridKnowledgeReply!!, actionOutcome.reply.ifBlank { actionOutcome.contextSummary })
            } else {
                actionOutcome.reply
            }
        } finally {
            try { if (wakeLock.isHeld) wakeLock.release() } catch (_: Exception) {}
            if (acquired != null) taskMutex.unlock()
        }
    }

    private data class ActionExecutionOutcome(
        val reply: String,
        val contextSummary: String,
    )

    private suspend fun executeSingleSkill(
        msg: IncomingMessage,
        userText: String,
        skillId: String,
        params: Map<String, Any>,
    ): ActionExecutionOutcome {
        val result = skillRunner.execute(
            skillId = skillId,
            params = params,
            chatId = msg.chatId,
            userId = msg.username ?: msg.chatId.toString(),
        )
        return when (result) {
            is SkillResult.Success -> {
                activityLog.log(userText, skillId, "success", result.message.take(100))
                ActionExecutionOutcome(
                    reply = deliverSuccess(msg.chatId, result.message, result.delivery),
                    contextSummary = result.message,
                )
            }
            is SkillResult.Failure -> {
                activityLog.log(userText, skillId, "failure", result.reason)
                ActionExecutionOutcome(
                    reply = "Error: ${result.reason}",
                    contextSummary = "Error: ${result.reason}",
                )
            }
            is SkillResult.NeedsConfirmation -> ActionExecutionOutcome(
                reply = result.prompt,
                contextSummary = result.prompt,
            )
        }
    }

    private suspend fun executeMultiStep(
        msg: IncomingMessage,
        userText: String,
        steps: List<SkillStep>,
    ): ActionExecutionOutcome {
        val totalSteps = steps.size
        val results = mutableListOf<String>()

        poller.sendMessage(msg.chatId, "Running $totalSteps steps...")

        for ((index, step) in steps.withIndex()) {
            val stepNum = index + 1
            poller.sendMessage(msg.chatId, "Step $stepNum/$totalSteps: ${step.skillId}...")

            val result = skillRunner.execute(
                skillId = step.skillId,
                params = step.params,
                chatId = msg.chatId,
                userId = msg.username ?: msg.chatId.toString(),
            )

            when (result) {
                is SkillResult.Success -> {
                    activityLog.log("$userText [step $stepNum]", step.skillId, "success", result.message.take(100))
                    val stepMsg = "OK Step $stepNum/$totalSteps: ${result.message}"
                    deliverSuccess(msg.chatId, stepMsg, result.delivery)
                    results.add(stepMsg)
                }
                is SkillResult.Failure -> {
                    activityLog.log("$userText [step $stepNum]", step.skillId, "failure", result.reason)
                    results.add("Error Step $stepNum (${step.skillId}): ${result.reason}")
                    break
                }
                is SkillResult.NeedsConfirmation -> {
                    results.add("Paused Step $stepNum (${step.skillId}): ${result.prompt}")
                    break
                }
            }
        }

        val contextSummary = if (results.isEmpty()) {
            "Completed $totalSteps steps."
        } else {
            results.joinToString("\n\n")
        }
        val visibleReply = if (results.all { it.startsWith("OK Step") }) {
            ""
        } else {
            results.filter { !it.startsWith("OK Step") }.joinToString("\n\n")
        }
        return ActionExecutionOutcome(
            reply = visibleReply,
            contextSummary = contextSummary,
        )
    }

    private suspend fun deliverSuccess(
        chatId: Long,
        message: String,
        delivery: DeliveryMode,
    ): String {
        return when (delivery) {
            DeliveryMode.SCREENSHOT_OR_TEXT -> sendWithScreenshot(chatId, message)
            DeliveryMode.LONG_TEXT -> {
                poller.sendLongMessage(chatId, message, parseMode = null)
                ""
            }
        }
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
                    return "" // already sent as photo â€” caller sends nothing
                }
            } catch (_: Exception) { /* fall through to text */ }
        }
        return message // no screenshot â€” return as plain text
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

    private suspend fun handleInfoCommand(
        chatId: Long,
        query: String,
        depth: ResearchDepth = ResearchDepth.QUICK,
    ): String {
        if (query.isBlank()) {
            return "Use `/info <topic>` for a quick web answer.\nUse `/research <topic>` for a deeper web search."
        }

        poller.sendTyping(chatId)
        val knowledgeReply = knowledgeBrain.answer(
            chatId = chatId,
            userMessage = query,
            contextHint = conversationContext.buildKnowledgeContext(chatId),
            depth = depth,
        )
        conversationContext.rememberKnowledge(
            chatId = chatId,
            query = knowledgeReply.query,
            topic = knowledgeReply.topic,
            summary = knowledgeReply.summary,
            sources = knowledgeReply.sources.map { it.url },
        )
        val commandLabel = if (depth == ResearchDepth.DEEP) "/research" else "/info"
        activityLog.log("$commandLabel $query", "knowledge", "success", knowledgeReply.summary.take(100))
        return knowledgeReply.reply
    }

    private suspend fun handleAttachmentMessage(msg: IncomingMessage, instructionOverride: String): String {
        val attachment = msg.attachment ?: return buildDocumentSummaryUsage()
        val logLabel = buildDocumentLogLabel(attachment, instructionOverride.ifBlank { msg.text })

        poller.sendTyping(msg.chatId)
        val downloaded = poller.downloadAttachment(attachment)
        if (downloaded == null) {
            activityLog.log(logLabel, "document", "failure", "Telegram download failed")
            return "I couldn't download ${attachment.fileName.ifBlank { "that file" }} from Telegram. Please try sending it again."
        }

        val instruction = instructionOverride.trim().ifBlank {
            msg.text
                .takeUnless { it.trim().startsWith("/summarize", ignoreCase = true) }
                ?.trim()
                .orEmpty()
        }.ifBlank {
            "Give me a concise summary with the key points, action items, dates, and important numbers."
        }

        return try {
            val summary = documentBrain.summarizeFile(
                file = downloaded.file,
                mimeType = downloaded.mimeType,
                displayName = downloaded.displayName,
                instruction = instruction,
            )
            activityLog.log(logLabel, "document", "success", summary.take(100))
            poller.sendLongMessage(msg.chatId, summary, parseMode = null)
            ""
        } catch (e: Exception) {
            val reason = e.message ?: "Document summary failed."
            activityLog.log(logLabel, "document", "failure", reason)
            "I downloaded ${downloaded.displayName}, but I couldn't summarize it: $reason"
        } finally {
            runCatching { downloaded.file.delete() }
        }
    }

    private fun buildDocumentSummaryUsage(): String = """
Send a PDF, DOCX, TXT, HTML, JSON, CSV, XML, or image to this bot with a caption like:
- `/summarize one paragraph summary`
- `two page summary focused on risks`
- `detailed summary with action items`

You can also open a document on the phone and say:
- `Summarize what's on screen in one page`
        """.trimIndent()

    private fun buildDocumentLogLabel(attachment: TelegramAttachment, instruction: String): String {
        val fileName = attachment.fileName.ifBlank {
            if (attachment.kind == TelegramAttachmentKind.PHOTO) "photo" else "document"
        }
        val cleanedInstruction = instruction.trim()
        return if (cleanedInstruction.isBlank()) {
            "summarize $fileName"
        } else {
            "summarize $fileName â€” ${cleanedInstruction.take(80)}"
        }
    }

    private fun buildHybridReply(knowledge: KnowledgeReply, actionResult: String): String {
        val sourceLines = knowledge.sources.take(2).mapIndexed { index, source ->
            "${index + 1}. ${source.title.take(70).sanitizeTelegramText()} (${source.domain.ifBlank { source.url }.sanitizeTelegramText()})"
        }.joinToString("\n")

        return buildString {
            append("Research: ${knowledge.summary.sanitizeTelegramText()}")
            append("\n\nAction: ")
            append(actionResult.ifBlank { "Done. Screenshot sent above if available." }.sanitizeTelegramText())
            if (sourceLines.isNotBlank()) {
                append("\n\nSources:\n")
                append(sourceLines)
            }
        }.take(3500)
    }

    private fun String.sanitizeTelegramText(): String =
        replace("*", "")
            .replace("`", "")
            .replace("[", "")
            .replace("]", "")
            .replace("_", "")

    private suspend fun runRoutine(routine: RoutineStore.Routine, msg: IncomingMessage): String {
        poller.sendMessage(msg.chatId, "🔗 *Running routine: ${routine.name}* (${routine.steps.size} steps)")
        routine.steps.forEachIndexed { i, step ->
            poller.sendMessage(msg.chatId, "▶️ Step ${i + 1}/${routine.steps.size}: _${step.take(60)}_")
            try {
                val result = handleMessage(msg.copy(text = step))
                if (result.isNotBlank()) poller.sendMessage(msg.chatId, result)
            } catch (e: Exception) {
                poller.sendMessage(msg.chatId, "❌ Step ${i + 1} failed: ${e.message}")
            }
        }
        return "✅ *Routine complete: ${routine.name}*"
    }

    suspend fun dispatchScheduledCommand(chatId: Long, command: String) {
        poller.sendMessage(chatId, "⏰ _Scheduled:_ $command")
        val fakeMsg = IncomingMessage(
            chatId = chatId,
            messageId = -1L,
            text = command,
            username = "scheduled",
            replyToMessageId = null,
            attachment = null,
        )
        val result = handleMessage(fakeMsg)
        if (result.isNotBlank()) poller.sendMessage(chatId, result)
    }

    private fun buildMutedListMessage(): String {
        val muted = muteStore.list()
        val granted = NotificationRelay.isPermissionGranted(context)
        val header = if (granted) "âœ… Notification relay: ON" else "âš ï¸ Notification relay needs permission. Settings â†’ Notifications â†’ Device & app notifications â†’ BharatDroid â†’ Allow."
        if (muted.isEmpty()) {
            return "$header\n\nðŸ”” No apps muted. All notifications are forwarded.\n\n*Commands:*\n`/mute WhatsApp` or `/mute com.whatsapp`\n`/unmute WhatsApp`"
        }
        val list = muted.mapIndexed { i, pkg ->
            "${i + 1}. ${NotificationRelay.labelFor(context, pkg)} (`$pkg`)"
        }.joinToString("\n")
        return "$header\n\nðŸ”• *Muted apps (${muted.size}):*\n$list\n\nUse `/unmute <name>` to re-enable."
    }

    private fun toggleMode(chatId: Long): String {
        // Cycle through SMART → AUTO → ASK → SMART
        val next = when (permissionsStore.mode) {
            PermissionsStore.Mode.SMART -> PermissionsStore.Mode.AUTO
            PermissionsStore.Mode.AUTO -> PermissionsStore.Mode.ASK
            PermissionsStore.Mode.ASK -> PermissionsStore.Mode.SMART
        }
        permissionsStore.mode = next
        return permissionsStore.buildStatusMessage()
    }

    private fun buildMemoryMessage(): String {
        val memories = userMemory.getAll()
        val status = if (userMemory.learningEnabled) "âœ… Learning: ON" else "âŒ Learning: OFF (toggle in Settings)"

        if (memories.isEmpty()) {
            return buildString {
                appendLine("ðŸ“­ No rules saved yet.")
                appendLine()
                appendLine("*Two ways to add rules:*")
                appendLine()
                appendLine("1ï¸âƒ£ *Just tell me naturally:*")
                appendLine("_\"Next time always confirm before sending\"_")
                appendLine("_\"Remember I want messages typed, not auto-sent\"_")
                appendLine("_\"Always sort Amazon by rating\"_")
                appendLine()
                appendLine("2ï¸âƒ£ *Direct command:*")
                appendLine("`/remember Always confirm before WhatsApp send`")
                appendLine()
                append(status)
            }
        }

        val list = memories.mapIndexed { i, m -> "${i + 1}. $m" }.joinToString("\n")
        return buildString {
            appendLine("ðŸ“Œ *Your Rules* (${memories.size}/30):")
            appendLine()
            appendLine(list)
            appendLine()
            appendLine(status)
            appendLine()
            appendLine("*Manage rules:*")
            appendLine("`/forget 3` â€” remove rule 3")
            appendLine("`/forget 1,3,5` â€” remove rules 1, 3 and 5")
            appendLine("`/forget 2-5` â€” remove rules 2 through 5")
            appendLine("`/forget` â€” clear all rules")
            appendLine("`/remember <rule>` â€” add a rule directly")
        }.trimEnd()
    }

    private fun buildTelegramCommands(): List<TelegramBotCommand> = listOf(
        TelegramBotCommand("start", "Show the welcome guide"),
        TelegramBotCommand("help", "Show the command list"),
        TelegramBotCommand("info", "Quick web answer without Chrome"),
        TelegramBotCommand("research", "Deeper web answer without Chrome"),
        TelegramBotCommand("summarize", "Summarize an attached document"),
        TelegramBotCommand("skills", "List all loaded skills"),
        TelegramBotCommand("status", "Show agent health and mode"),
        TelegramBotCommand("history", "Show recent activity"),
        TelegramBotCommand("memory", "Show saved rules"),
        TelegramBotCommand("remember", "Save a new rule"),
        TelegramBotCommand("forget", "Delete rules or clear them all"),
        TelegramBotCommand("place", "Saved places — /place save home <address>"),
        TelegramBotCommand("shortcut", "Quick shortcuts — /shortcut add morning = ..."),
        TelegramBotCommand("permissions", "View or change permission mode"),
        TelegramBotCommand("knowledge", "Show learned app knowledge"),
        TelegramBotCommand("knowledge_clear", "Clear all or one app's knowledge"),
        TelegramBotCommand("muted", "Show muted notification apps"),
        TelegramBotCommand("mute", "Mute notification forwarding for an app"),
        TelegramBotCommand("unmute", "Unmute notification forwarding"),
        TelegramBotCommand("mode", "Toggle Ask Permission mode"),
        TelegramBotCommand("clear", "Clear chat memory"),
        TelegramBotCommand("install", "Install a community skill"),
        TelegramBotCommand("uninstall", "Remove a community skill"),
        TelegramBotCommand("screenshot", "Take and send a screenshot instantly"),
        TelegramBotCommand("open", "Open any app — /open Swiggy"),
        TelegramBotCommand("schedule", "Schedule a task — /schedule 7pm order pizza"),
        TelegramBotCommand("routine", "Run a command chain — /routine morning"),
    )

    private fun parseTelegramCommand(input: String): ParsedTelegramCommand? {
        val trimmed = normalizeIncomingTelegramText(input).trim()
        if (!trimmed.startsWith("/")) return null

        val commandEnd = trimmed.indexOfFirst { it.isWhitespace() }
            .let { if (it == -1) trimmed.length else it }
        val commandToken = trimmed.substring(1, commandEnd)
        if (commandToken.isBlank()) return null

        val name = commandToken.substringBefore('@').lowercase()
        if (name.isBlank() || name.any { !(it.isLowerCase() || it.isDigit() || it == '_') }) {
            return null
        }

        return ParsedTelegramCommand(
            name = name,
            args = trimmed.substring(commandEnd).trimStart(),
        )
    }

    private fun extractResearchCommand(input: String): ResearchCommand? {
        val trimmed = normalizeIncomingTelegramText(input).trim()
        val infoMatch = Regex("^/info(?:@[A-Za-z0-9_]+)?(?:\\s+(.*))?$", RegexOption.IGNORE_CASE)
            .matchEntire(trimmed)
        if (infoMatch != null) {
            return ResearchCommand(
                depth = ResearchDepth.QUICK,
                query = infoMatch.groupValues.getOrElse(1) { "" }.trim(),
            )
        }

        val researchMatch = Regex("^/research(?:@[A-Za-z0-9_]+)?(?:\\s+(.*))?$", RegexOption.IGNORE_CASE)
            .matchEntire(trimmed)
        if (researchMatch != null) {
            return ResearchCommand(
                depth = ResearchDepth.DEEP,
                query = researchMatch.groupValues.getOrElse(1) { "" }.trim(),
            )
        }

        return null
    }

    /**
     * Detects compound commands like "order biryani from Swiggy and pay Priya 200".
     * Returns a Pair of the two sub-commands if both look actionable, null otherwise.
     */
    private fun splitCompoundCommand(input: String): Pair<String, String>? {
        val actionVerbs = setOf(
            "order", "book", "search", "find", "send", "pay", "open", "play",
            "navigate", "go to", "call", "message", "dm", "share", "post",
            "buy", "add", "create", "set", "read", "check", "show", "recharge",
            "transfer", "download", "install", "remind", "track", "compare",
        )
        val andIndex = input.lowercase().indexOf(" and ")
        if (andIndex < 3) return null
        val part1 = input.substring(0, andIndex).trim()
        val part2 = input.substring(andIndex + 5).trim()
        if (part1.length < 5 || part2.length < 5) return null
        fun isActionable(s: String): Boolean {
            val l = s.lowercase()
            return actionVerbs.any { l.startsWith(it) || l.contains(" $it ") }
        }
        if (!isActionable(part1) || !isActionable(part2)) return null
        return Pair(part1, part2)
    }

    private fun normalizeIncomingTelegramText(input: String): String =
        input
            .replace("\uFEFF", "")
            .replace("\u200B", "")
            .replace("\u200C", "")
            .replace("\u200D", "")
            .replace("\u2060", "")
            .replace('\u00A0', ' ')

    private data class ResearchCommand(
        val depth: ResearchDepth,
        val query: String,
    )

    private data class ParsedTelegramCommand(
        val name: String,
        val args: String,
    )

    private fun buildWelcomeMessage(): String = """
*BharatDroid Agent* is live on this phone.

Tell me what to do â€” English or Hindi. I'll do it.

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
- "Summarize what's on screen in one page"

*Documents:*
- Send a PDF/DOCX/TXT/image with caption: `one paragraph summary`
- Send a document with caption: `/summarize two page summary focused on action items`
- "Read the latest PDF from WhatsApp and send me a one page summary"
- "Open this article and give me a detailed summary on Telegram"
- "Send invoice.pdf from Downloads to Rahul on WhatsApp"

*Web Research:*
- "/info Who is Sundar Pichai?"
- "/info Tell me about this company"
- "/research Find out the latest about Nvidia"
- "/research Research this person deeply"

*More:*
- “Navigate to Gateway of India”
- “Message mom on WhatsApp: coming home”
- “Send the latest PDF to HR on WhatsApp”
- “Play Alan Walker Faded on YouTube”
- “Earbuds under 1000 on Amazon”
- “Open calculator and compute 25 * 4”

*Power Features:*
- “again” or “repeat” — re-run the last command instantly
- “order pizza and pay Priya 200” — compound commands (runs both)
- Save places: `/place save home Koramangala, Bangalore`
  Then say: _”take me home”_ or _”Ola to work”_ — I fill in the address
- Save shortcuts: `/shortcut add morning = give me my morning brief`
  Then just type: _”morning”_ to run it
📸 /screenshot — instant screen capture sent to Telegram
📱 /open <app> — launch any installed app
⏰ /schedule <time> <cmd> — schedule one-time or daily tasks
🔗 /routine <name> — run named command chains
🎙️ Voice notes — send audio, I’ll transcribe and execute

*Commands:*
/help — show this guide
/skills — list all skills
/status — agent health check
/history — recent activity
/info <topic> — quick web research without opening Chrome
/research <topic> — deeper web research without opening Chrome
/summarize — summarize an attached document
/memory — see your saved rules
/remember <rule> — add a rule directly
/forget 3 — delete rule #3
/place — manage saved places (home, work, gym…)
/shortcut — manage quick shortcuts
/knowledge — see what I’ve learned per app
/muted — notification relay status + muted apps
/mute <app> — stop forwarding notifications from that app
/unmute <app> — re-enable notifications from that app
/mode — toggle Ask Permission / Just Do It
/clear — reset conversation memory
/install <url> — add community skill

💡 *Tips:*
- Teach me naturally: _”next time always confirm before sending”_
- Or add a rule directly: `/remember Always sort Amazon results by rating`
- Rules I learn are *mandatory* — I follow them every time, not just sometimes.
- I also learn each app’s layout — every task makes me faster.
    """.trimIndent()

    // ── Voice note transcription ─────────────────────────────────────────────
    private suspend fun handleVoiceNote(msg: IncomingMessage): String {
        // LLMClient does not currently support audio transcription.
        // Return a graceful message guiding the user to a compatible provider.
        return "🎙️ Voice notes work with Gemini 2.0+. Set Gemini as your AI provider in Settings."
    }

    // ── Device info helpers (for /status) ────────────────────────────────────
    private fun getBatteryLevel(): String {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            if (charging) "$pct% ⚡" else "$pct%"
        } catch (_: Exception) { "N/A" }
    }

    private fun getWifiName(): String {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ssid = wm.connectionInfo?.ssid
            if (!ssid.isNullOrBlank() && ssid != "<unknown ssid>") {
                ssid.removeSurrounding("\"")
            } else {
                // Fallback: check if connected to any network
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val net = cm.activeNetwork
                val caps = cm.getNetworkCapabilities(net)
                when {
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobile data"
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
                    else -> "Offline"
                }
            }
        } catch (_: Exception) { "N/A" }
    }

    private fun getFreeStorage(): String {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val bytesAvailable = stat.blockSizeLong * stat.availableBlocksLong
            val gb = bytesAvailable / (1024.0 * 1024.0 * 1024.0)
            if (gb >= 1.0) "%.1f GB".format(gb) else "${(bytesAvailable / (1024 * 1024))} MB"
        } catch (_: Exception) { "N/A" }
    }

    private fun getUptimeString(): String {
        return try {
            val ms = SystemClock.elapsedRealtime()
            val hours = ms / 3_600_000
            val minutes = (ms % 3_600_000) / 60_000
            "${hours}h ${minutes}m"
        } catch (_: Exception) { "N/A" }
    }

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
                sb.appendLine("*${s.name}* â€” ${s.description}")
            }
        }

        grouped[false]?.let { community ->
            sb.appendLine("\n*Community Skills:*")
            community.forEach { s ->
                sb.appendLine("${s.name} by ${s.author} â€” ${s.description}")
            }
        }

        return sb.toString().trimEnd()
    }

    private fun buildStatusMessage(): String {
        val serviceOk = AgentAccessibilityService.isConnected
        val count = activityLog.todayCount()
        val prefs = context.getSharedPreferences("bharatdroid", Context.MODE_PRIVATE)
        val mode = if (prefs.getBoolean("ask_permission", true)) "Ask Permission" else "Just Do It"
        val agentProvider = prefs.getString("agent_ai_provider", prefs.getString("ai_provider", "GEMINI")) ?: "GEMINI"
        val agentModel = prefs.getString("agent_ai_model", prefs.getString("ai_model", "")) ?: ""
        val researchKey = prefs.getString("research_ai_key", "")?.trim().orEmpty()
        val researchProvider = if (researchKey.isBlank()) {
            "$agentProvider (fallback)"
        } else {
            prefs.getString("research_ai_provider", agentProvider) ?: agentProvider
        }
        val researchModel = if (researchKey.isBlank()) {
            agentModel
        } else {
            prefs.getString("research_ai_model", "") ?: ""
        }
        val knownApps = appKnowledge.getAppList().size
        val memCount = userMemory.getAll().size
        val notifGranted = NotificationRelay.isPermissionGranted(context)
        val mutedCount = muteStore.list().size
        val batteryPct = getBatteryLevel()
        val wifiName = getWifiName()
        val storageFree = getFreeStorage()
        val uptime = getUptimeString()
        return """
*BharatDroid Status*

Agent: Running
Accessibility: ${if (serviceOk) "âœ… Connected" else "âŒ Not connected"}
Notification relay: ${if (notifGranted) "âœ… Active${if (mutedCount > 0) " ($mutedCount muted)" else ""}" else "âš ï¸ Off â€” grant in Settings â†’ Notification Access"}
Skills: ${skillRunner.listSkills().size} loaded
Mode: $mode
Agent AI: $agentProvider ${if (agentModel.isNotBlank()) "($agentModel)" else ""}
Research AI: $researchProvider ${if (researchModel.isNotBlank()) "($researchModel)" else ""}
Brains: Web knowledge + device actions
Today: $count commands processed
Apps learned: $knownApps  |  Preferences: $memCount

📱 *Device*
Battery: $batteryPct  |  Network: $wifiName  |  Free: $storageFree  |  Up: $uptime
        """.trimIndent()
    }
}

data class AgentConfig(
    val telegramBotToken: String,
    val agentApiKey: String,
    val researchApiKey: String,
    val authorizedChatIds: Set<Long>,
    val askPermission: Boolean = true,
    val agentProvider: AIProvider = AIProvider.GEMINI,
    val agentModel: String = "",
    val researchProvider: AIProvider = AIProvider.GEMINI,
    val researchModel: String = "",
)
