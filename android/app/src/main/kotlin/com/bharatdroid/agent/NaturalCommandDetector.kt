package com.bharatdroid.agent

/**
 * NaturalCommandDetector — converts free-form user phrases into internal slash commands.
 *
 * The slash was invented for public Telegram bots. BharatClaw is a personal agent —
 * the user should never need to type "/" for anything. This class is the knowledge
 * layer that spots intent in plain language and maps it to the right command.
 *
 * It runs BEFORE the AI brain on every message, so there's zero latency overhead
 * for these detections (pure Kotlin regex, no API call).
 *
 * Priority order matches handleMessage():
 *   screenshot → status → routine → schedule/remind → open → permissions → place → shortcut
 *
 * Each rule returns a String that AgentOrchestrator re-dispatches as if the user
 * had typed that slash command — so all existing logic applies unchanged.
 */
object NaturalCommandDetector {

    /**
     * Given raw user input, returns the equivalent slash command string if a
     * system-command intent is detected, or null to let normal AI routing proceed.
     *
     * Examples:
     *   "take a screenshot" → "/screenshot"
     *   "remind me at 7pm to order pizza" → "/schedule 7pm order pizza"
     *   "run my morning routine" → "/routine morning"
     *   "open Swiggy" → "/open Swiggy"
     *   "how's the agent" → "/status"
     *   "save home as Koramangala Bangalore" → "/place save home Koramangala Bangalore"
     *   "add shortcut pizza = order chicken pizza from Swiggy" → "/shortcut add pizza = order chicken pizza from Swiggy"
     *   "turn off payment confirmations" → "/permissions payment off"
     *   "stop asking me before ordering" → "/permissions ordering off"
     */
    fun detect(input: String): String? {
        val t = input.trim()
        val l = t.lowercase()

        return detectScreenshot(l)
            ?: detectStatus(l)
            ?: detectRoutine(l, t)
            ?: detectSchedule(l, t)
            ?: detectDownload(l)
            ?: detectOpen(l, t)
            ?: detectPermissions(l)
            ?: detectPlace(l, t)
            ?: detectShortcut(l, t)
            ?: detectSystemList(l)
    }

    // ── Screenshot ───────────────────────────────────────────────────────────

    private fun detectScreenshot(l: String): String? {
        val exactMatch = l in setOf(
            "screenshot", "screen shot", "screencap", "screen cap",
            "take screenshot", "send screenshot", "capture screen",
        )
        if (exactMatch) return "/screenshot"

        val patterns = listOf(
            Regex("""(take|grab|send|get|capture|show)(?: me)?(?: a| the| my)? (?:screenshot|screen shot|screencap|screen cap|screen)"""),
            Regex("""(?:what(?:'s| is)(?: on)? (?:my |the )?screen)"""),
            Regex("""(?:screenshot|screengrab|screenrecord)"""),
        )
        return if (patterns.any { it.containsMatchIn(l) }) "/screenshot" else null
    }

    // ── Status ───────────────────────────────────────────────────────────────

    private fun detectStatus(l: String): String? {
        val exactMatch = l in setOf(
            "status", "agent status", "show status", "check status",
            "are you running", "are you on", "is the agent running",
            "how are you", "how's the agent", "how is the agent",
        )
        if (exactMatch) return "/status"

        val patterns = listOf(
            Regex("""(?:what(?:'s| is)|show|check|tell me|give me)(?: my| the| agent)? (?:status|uptime|health|stats)"""),
            Regex("""(?:how(?:'s| is)(?: the| my)? (?:agent|bot|assistant|BharatClaw) (?:doing|running|working))"""),
            Regex("""(?:battery|wifi|network|storage)(?: status| level| info)?"""),
        )
        return if (patterns.any { it.containsMatchIn(l) }) "/status" else null
    }

    // ── Routine ──────────────────────────────────────────────────────────────

    private fun detectRoutine(l: String, original: String): String? {
        // "run/start/do/execute my morning routine" → /routine morning
        val runPattern = Regex(
            """(?:run|start|do|execute|begin|trigger|play|go with)(?: my| the)?\s+(.+?)\s+routine""",
            RegexOption.IGNORE_CASE
        )
        runPattern.find(l)?.let { m ->
            val name = m.groupValues[1].trim()
            if (name.isNotBlank() && name.length <= 40) return "/routine $name"
        }

        // "morning routine" / "evening routine" (bare, no verb) → /routine morning
        val barePattern = Regex("""^([\w\s]{1,30}?)\s+routine$""", RegexOption.IGNORE_CASE)
        barePattern.find(l)?.let { m ->
            val name = m.groupValues[1].trim()
            if (name.isNotBlank() && name !in setOf("add", "create", "save", "delete", "remove", "list", "show")) {
                return "/routine $name"
            }
        }

        // "list my routines" / "show routines" / "what routines do I have"
        val listPattern = Regex("""(?:list|show|what|see)(?: my| all)? routines?""")
        if (listPattern.containsMatchIn(l)) return "/routine"

        return null
    }

    // ── Schedule / Remind ────────────────────────────────────────────────────

    private fun detectSchedule(l: String, original: String): String? {
        // "list schedules" / "show what's scheduled" / "what's scheduled"
        val listPatterns = listOf(
            Regex("""(?:list|show|see|what(?:'s| is))(?: my| all)? schedule[ds]?"""),
            Regex("""what(?:'s| is) (?:scheduled|planned|coming up)"""),
        )
        if (listPatterns.any { it.containsMatchIn(l) }) return "/schedule list"

        // "cancel schedule 2" / "remove scheduled task 3"
        val cancelPattern = Regex("""(?:cancel|remove|delete) (?:schedule|scheduled task|reminder|alarm)\s*#?(\d+)""")
        cancelPattern.find(l)?.let { m ->
            return "/schedule cancel ${m.groupValues[1]}"
        }

        // "remind me at/in/on/tonight/tomorrow..." → extract time + command
        val remindPatterns = listOf(
            // "remind me at 7pm to order pizza"
            Regex("""remind(?: me)? (?:at|by) ([\d:apm]+)\s+(?:to\s+)?(.+)""", RegexOption.IGNORE_CASE),
            // "remind me in 30 minutes to check email"
            Regex("""remind(?: me)? (in \d+ (?:minutes?|hours?|mins?|hrs?))\s+(?:to\s+)?(.+)""", RegexOption.IGNORE_CASE),
            // "remind me tomorrow at 8am to do brief"
            Regex("""remind(?: me)? (tomorrow(?: at)? [\d:apm]+)\s+(?:to\s+)?(.+)""", RegexOption.IGNORE_CASE),
            // "remind me tonight at 9 to call mom"
            Regex("""remind(?: me)? (tonight(?: at)? [\d:apm]*)\s+(?:to\s+)?(.+)""", RegexOption.IGNORE_CASE),
            // "remind me daily at 7am to do morning brief"
            Regex("""remind(?: me)? (daily(?: at)? [\d:apm]+)\s+(?:to\s+)?(.+)""", RegexOption.IGNORE_CASE),
        )
        for (pattern in remindPatterns) {
            pattern.find(l)?.let { m ->
                val time = m.groupValues[1].trim()
                val command = m.groupValues[2].trim()
                if (time.isNotBlank() && command.isNotBlank()) {
                    return "/schedule $time $command"
                }
            }
        }

        // "set a reminder for 7pm — order pizza"
        val setReminderPattern = Regex(
            """set(?: a| an)? (?:reminder|alarm|timer)(?: for)? ([\d:apm\w\s]+?) (?:to |for |— )?(.+)""",
            RegexOption.IGNORE_CASE
        )
        setReminderPattern.find(l)?.let { m ->
            val time = m.groupValues[1].trim()
            val command = m.groupValues[2].trim()
            if (time.isNotBlank() && command.isNotBlank()) {
                return "/schedule $time $command"
            }
        }

        // "schedule order pizza for 7pm" / "schedule at 7pm order pizza"
        val scheduleKeyword = Regex(
            """^schedule\s+(?:at\s+|for\s+)?([\d:apm]+(?:\s+(?:am|pm))?|tomorrow|daily)\s+(.+)""",
            RegexOption.IGNORE_CASE
        )
        scheduleKeyword.find(l)?.let { m ->
            val time = m.groupValues[1].trim()
            val command = m.groupValues[2].trim()
            if (time.isNotBlank() && command.isNotBlank()) {
                return "/schedule $time $command"
            }
        }

        // "every day at 8am do morning brief" / "daily at 7am run morning brief"
        val dailyPattern = Regex(
            """(?:every\s+day|daily|each\s+day)(?: at)?\s+([\d:apm]+)\s+(?:do\s+|run\s+|execute\s+)?(.+)""",
            RegexOption.IGNORE_CASE
        )
        dailyPattern.find(l)?.let { m ->
            val time = m.groupValues[1].trim()
            val command = m.groupValues[2].trim()
            if (time.isNotBlank() && command.isNotBlank()) {
                return "/schedule daily $time $command"
            }
        }

        return null
    }

    // ── Download / Install App ───────────────────────────────────────────────

    private fun detectDownload(l: String): String? {
        val pattern = Regex(
            """^(?:download|install|get|set up|setup)\s+(?:the\s+)?(.+?)(?:\s+(?:app|application|apk|from\s+play\s+store|on\s+(?:my\s+)?phone))?$""",
            RegexOption.IGNORE_CASE
        )
        val m = pattern.find(l) ?: return null
        val app = m.groupValues[1].trim().takeIf { it.isNotBlank() && it.length <= 40 } ?: return null
        return "/download $app"
    }

    // ── Open App ─────────────────────────────────────────────────────────────

    private val NAVIGATION_KEYWORDS = setOf(
        "in", "on", "from", "at", "using", "via", "with", "through", "and", "to", "the",
        "me", "my", "an", "a", "for", "of",
    )

    private fun detectOpen(l: String, original: String): String? {
        // "open Swiggy" / "launch Chrome" / "start Settings" — but NOT "open maps and search for X"
        // Guard: must be a short command (≤5 words) with no action words after the app name
        val openPattern = Regex(
            """^(?:open|launch|start|fire up|pull up)\s+(.+)$""",
            RegexOption.IGNORE_CASE
        )
        openPattern.find(l)?.let { m ->
            val rest = m.groupValues[1].trim()
            val words = rest.split(Regex("""\s+"""))
            // If it's just an app name (1-2 words, no conjunctions), treat as /open
            // If it has more words like "open swiggy and order biryani", let AI handle
            val hasActionAfter = words.size > 2 || words.any { it in NAVIGATION_KEYWORDS }
            if (!hasActionAfter || words.size == 1) {
                return "/open $rest"
            }
            // Two-word case: check if it looks like "open X" not "open X and do Y"
            if (words.size == 2 && words[1] !in NAVIGATION_KEYWORDS) {
                return "/open $rest"
            }
        }
        return null
    }

    // ── Permissions ──────────────────────────────────────────────────────────

    private fun detectPermissions(l: String): String? {
        // "show permissions" / "what's the permission mode" / "permission settings"
        val viewPatterns = listOf(
            Regex("""(?:show|what(?:'s| is)|check|see)(?: my| the)? (?:permission|permission mode|confirmation|confirmations)"""),
            Regex("""^permissions?$"""),
        )
        if (viewPatterns.any { it.containsMatchIn(l) }) return "/permissions"

        // Mode switching
        if (l.contains("auto mode") || Regex("""(?:just do it|no confirmations?|stop asking|don't ask|dont ask|skip confirmations?)""").containsMatchIn(l))
            return "/permissions auto"
        if (l.contains("smart mode") || l.contains("ask for important"))
            return "/permissions smart"
        if (l.contains("ask mode") || l.contains("ask for everything") || l.contains("always ask"))
            return "/permissions ask"

        // Category toggles
        // "turn off payment confirmations" → /permissions payment off
        // "stop confirming payments" → /permissions payment off
        // "always ask before paying" → /permissions payment on
        val categories = mapOf(
            "payment" to listOf("payment", "paying", "pay", "gpay", "phonepe", "upi"),
            "booking" to listOf("booking", "booked", "cab", "ride", "ola", "uber"),
            "ordering" to listOf("ordering", "order", "food", "swiggy", "zomato"),
            "purchase" to listOf("purchase", "buy", "buying", "amazon", "flipkart", "shopping"),
            "messaging" to listOf("message", "messaging", "whatsapp", "text", "chat"),
        )
        val offSignals = listOf("turn off", "disable", "stop", "don't ask", "dont ask", "no confirmation", "skip", "without asking")
        val onSignals = listOf("turn on", "enable", "always ask", "confirm", "ask before", "ask me")

        for ((category, keywords) in categories) {
            val matchesCategory = keywords.any { l.contains(it) }
            if (!matchesCategory) continue
            val isOff = offSignals.any { l.contains(it) }
            val isOn = onSignals.any { l.contains(it) }
            if (isOff) return "/permissions $category off"
            if (isOn) return "/permissions $category on"
        }

        return null
    }

    // ── Saved Places ─────────────────────────────────────────────────────────

    private fun detectPlace(l: String, original: String): String? {
        // "list my places" / "show saved places" / "what places do I have"
        val listPatterns = listOf(
            Regex("""(?:list|show|see|what)(?: my| all| saved)? places?"""),
            Regex("""(?:my saved|saved) (?:places?|locations?|addresses?)"""),
        )
        if (listPatterns.any { it.containsMatchIn(l) }) return "/place list"

        // "save home as Koramangala Bangalore" → /place save home Koramangala Bangalore
        val saveAsPattern = Regex(
            """(?:save|set|add|remember|store)(?: my)? (\w+) (?:as|to|=)\s+(.+)""",
            RegexOption.IGNORE_CASE
        )
        saveAsPattern.find(l)?.let { m ->
            val name = m.groupValues[1].trim()
            val address = m.groupValues[2].trim()
            if (name.isNotBlank() && address.isNotBlank() && name.length <= 20) {
                return "/place save $name $address"
            }
        }

        // "remember that Koramangala is my home" → /place save home Koramangala
        val rememberThatPattern = Regex(
            """remember (?:that )?(.+?) is(?: my)? (\w+)""",
            RegexOption.IGNORE_CASE
        )
        rememberThatPattern.find(l)?.let { m ->
            val address = m.groupValues[1].trim()
            val name = m.groupValues[2].trim()
            val reservedNames = setOf("home", "work", "office", "gym", "school", "college")
            if (name in reservedNames && address.isNotBlank()) {
                return "/place save $name $address"
            }
        }

        // "delete place home" / "remove my home address"
        val deletePattern = Regex(
            """(?:delete|remove|forget)(?: my)? (?:place\s+)?(\w+)(?: (?:address|place|location))?""",
            RegexOption.IGNORE_CASE
        )
        deletePattern.find(l)?.let { m ->
            val name = m.groupValues[1].trim()
            val knownPlaces = setOf("home", "work", "office", "gym", "school", "college")
            if (name in knownPlaces) return "/place delete $name"
        }

        return null
    }

    // ── Shortcuts ────────────────────────────────────────────────────────────

    private fun detectShortcut(l: String, original: String): String? {
        // "list my shortcuts" / "show shortcuts"
        val listPatterns = listOf(
            Regex("""(?:list|show|see|what)(?: my| all| saved)? shortcuts?"""),
            Regex("""(?:my saved|saved) shortcuts?"""),
        )
        if (listPatterns.any { it.containsMatchIn(l) }) return "/shortcut list"

        // "add shortcut pizza = order chicken pizza from Swiggy"
        val addPattern = Regex(
            """(?:add|create|save|make)(?: a)? shortcut\s+(\w[\w\s]*?)\s*=\s*(.+)""",
            RegexOption.IGNORE_CASE
        )
        addPattern.find(original)?.let { m ->
            val name = m.groupValues[1].trim()
            val command = m.groupValues[2].trim()
            if (name.isNotBlank() && command.isNotBlank()) {
                return "/shortcut add $name = $command"
            }
        }

        // "delete shortcut pizza" / "remove shortcut morning"
        val deletePattern = Regex(
            """(?:delete|remove)(?: a)? shortcut\s+(\w+)""",
            RegexOption.IGNORE_CASE
        )
        deletePattern.find(l)?.let { m ->
            val name = m.groupValues[1].trim()
            if (name.isNotBlank()) return "/shortcut delete $name"
        }

        return null
    }

    // ── System list catch-all ────────────────────────────────────────────────

    private fun detectSystemList(l: String): String? {
        // "show history" / "recent commands"
        if (Regex("""(?:show|see|list|what(?:'s| is))(?: my| the)? (?:history|recent commands?|past commands?)""").containsMatchIn(l) ||
            l == "history") return "/history"

        // "show memory" / "what do you remember" / "my rules"
        if (Regex("""(?:show|see|what)(?: do you| (?:do )?I)? remember|(?:my )?(?:preferences?|memory|rules?)""").containsMatchIn(l) ||
            l == "memory") return "/memory"

        // "list skills" / "what can you do" / "show all skills"
        if (Regex("""(?:list|show|see|what)(?: all| my)? skills?|what can you do""").containsMatchIn(l) ||
            l == "skills") return "/skills"

        // "clear memory" / "clear conversation" / "reset"
        if (Regex("""^(?:clear|reset)(?: (?:memory|conversation|history|context|chat))?$""").containsMatchIn(l))
            return "/clear"

        // "mute WhatsApp" / "stop WhatsApp notifications"
        val mutePattern = Regex("""^(?:mute|silence|stop notifications? from|block notifications? from)\s+(.+)$""")
        mutePattern.find(l)?.let { m ->
            val app = m.groupValues[1].trim()
            if (app.isNotBlank()) return "/mute $app"
        }

        // "unmute WhatsApp" / "re-enable WhatsApp notifications"
        val unmutePattern = Regex("""^(?:unmute|re-?enable notifications? for|unsilence)\s+(.+)$""")
        unmutePattern.find(l)?.let { m ->
            val app = m.groupValues[1].trim()
            if (app.isNotBlank()) return "/unmute $app"
        }

        return null
    }
}
