package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.ScreenAgent
import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class YouTubeSkill : Skill {

    override val manifest = SkillManifest(
        id = "youtube",
        name = "YouTube",
        version = "8.0.0",
        description = "Play videos, search, go to channels, browse subscriptions — any YouTube task",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK,
        ),
        allowedPackages = setOf("com.google.android.youtube"),
        exampleParamsHint = """{"action": "play", "query": "Arijit Singh Tum Hi Ho"}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase() ?: "play"
        val query = params["query"] as? String ?: ""
        val goal = params["goal"] as? String ?: ""

        runner.openApp("com.google.android.youtube")
        runner.waitForApp("com.google.android.youtube", timeoutMs = 6000)
        delay(1000)
        // Dismiss any startup popups (location, notifications, etc)
        dismissYouTubePopups(runner)
        delay(300)

        return when (action) {

            "play", "search", "lyrics" -> {
                if (query.isBlank()) return SkillResult.Failure("What do you want to play or search on YouTube?")
                val searchQuery = if (action == "lyrics" && !query.contains("lyrics", ignoreCase = true))
                    "$query lyrics" else query
                playVideo(runner, agent, searchQuery)
            }

            "subscriptions", "subs" -> {
                // Tap subscriptions tab in bottom nav
                tapBottomNav(runner, "Subscriptions")
                delay(800)
                val screen = runner.readScreen()
                SkillResult.Success("YouTube Subscriptions:\n```\n${screen.take(600)}\n```")
            }

            "home" -> {
                tapBottomNav(runner, "Home")
                delay(600)
                SkillResult.Success("Opened YouTube Home.")
            }

            "channel", "goal" -> {
                // Complex multi-step task — give AI full context
                // Use the goal text directly; extract a clean search term from it
                val rawGoal = goal.ifBlank { query }
                val fullGoal = buildFullGoal(rawGoal, query)
                val result = agent.executeGoal(runner, fullGoal, maxSteps = 35)
                SkillResult.Success(result)
            }

            else -> {
                val fallback = goal.ifBlank { query.ifBlank { action } }
                val fullGoal = buildFullGoal(fallback, query)
                val result = agent.executeGoal(runner, fullGoal, maxSteps = 30)
                SkillResult.Success(result)
            }
        }
    }

    // ── Open search, type query, wait for results, tap first real video ───────
    private suspend fun playVideo(
        runner: SandboxedRunner,
        agent: ScreenAgent,
        searchQuery: String,
    ): SkillResult {
        // Step 1: Get to search input
        // Check current state
        val screen0 = runner.readScreen()
        val alreadyInSearch = screen0.contains("Search YouTube", ignoreCase = true)
        val alreadyPlaying = screen0.contains("Subscribe", ignoreCase = true)
            && (screen0.contains("Comments", ignoreCase = true) || screen0.contains("Like", ignoreCase = true))

        if (alreadyPlaying) {
            // Already on a video — go back to home first to search fresh
            tapBottomNav(runner, "Home")
            delay(800)
        }

        if (!alreadyInSearch) {
            // Tap the search icon at the top of YouTube
            val searchIconTapped = tapSearchIcon(runner)
            if (!searchIconTapped) {
                // fallback: look for it in elements
                val (_, sH) = runner.getScreenSize()
                val el = runner.getClickableElements().firstOrNull { e ->
                    val t = (e.text + e.hint + e.contentDescription).lowercase()
                    t.contains("search") && !t.contains("youtube") && e.centerY < sH * 0.12f
                }
                if (el != null) runner.tapAtPoint(el.centerX.toFloat(), el.centerY.toFloat())
            }
            delay(600)
        }

        // Step 2: Type query — clear old query first, then type fresh
        runner.clearField() // safe clear via ACTION_SET_TEXT(""), no selection chaos
        delay(100)
        val typed = runner.typeInFieldWithHint("Search YouTube", searchQuery)
            || runner.typeInFieldWithHint("Search", searchQuery)
            || runner.typeReliably(searchQuery)

        if (!typed) return SkillResult.Failure("Could not type in YouTube search field")
        delay(300)
        runner.pressEnter()
        delay(2000) // wait for results

        // Step 3: Dismiss any history/privacy banners WITHOUT going back
        dismissYouTubePopups(runner)
        delay(400)

        // Step 4: Tap the first real video result
        // CRITICAL: Do NOT press back — just find and tap a video result
        return tapFirstVideo(runner, agent, searchQuery)
    }

    // ── Tap the search icon without pressing back ─────────────────────────────
    private suspend fun tapSearchIcon(runner: SandboxedRunner): Boolean {
        val (screenW, screenH) = runner.getScreenSize()
        // Try elements first — use screen fractions, not hardcoded pixels
        val elements = runner.getClickableElements()
        val searchEl = elements.firstOrNull { el ->
            val combined = (el.text + el.hint + el.contentDescription + el.viewId).lowercase()
            combined.contains("search") && el.centerY < screenH * 0.12f && el.width < screenW * 0.2f
        }
        if (searchEl != null) {
            return runner.tapAtPoint(searchEl.centerX.toFloat(), searchEl.centerY.toFloat())
        }
        // Try by text
        return runner.tapByText("Search")
    }

    // ── Tap first real video (no back, no home, just tap and stay) ────────────
    private suspend fun tapFirstVideo(
        runner: SandboxedRunner,
        agent: ScreenAgent,
        searchQuery: String,
        retryCount: Int = 0,  // prevent infinite recursion
    ): SkillResult {
        val (w, h) = runner.getScreenSize()
        val elements = runner.getClickableElements()

        // Filter to likely video title elements:
        // - Not a nav button, filter chip, or banner
        // - Has meaningful text (>10 chars)
        // - In the middle/lower portion of screen (past the filter bar)
        val skipTexts = setOf(
            "all", "videos", "channels", "playlists", "shorts",
            "turn on", "history", "got it", "no thanks", "dismiss",
            "home", "subscriptions", "library", "notifications", "search",
        )
        val videoEls = elements.filter { el ->
            val t = el.text.lowercase().trim()
            el.isClickable
                && el.text.length > 10
                && el.centerY > h * 0.22f
                && skipTexts.none { skip -> t == skip || t.startsWith(skip) }
                && !el.text.contains("history", ignoreCase = true)
        }

        if (videoEls.isNotEmpty()) {
            val target = videoEls.first()
            val ok = runner.tapAtPoint(target.centerX.toFloat(), target.centerY.toFloat())
            if (ok) {
                delay(2000)
                val afterScreen = runner.readScreen()
                // Success checks — video OR channel page
                val isVideoPlaying = afterScreen.contains("Comments", ignoreCase = true)
                    || afterScreen.contains("Like", ignoreCase = true)
                val isOnChannel = isChannelPage(afterScreen)
                if (isVideoPlaying) {
                    return SkillResult.Success("▶️ Playing: *${target.text.take(80)}*")
                }
                if (isOnChannel) {
                    return SkillResult.Success("📺 Opened channel: *${target.text.take(80)}*")
                }
                // Something else opened — still count as success
                return SkillResult.Success("Opened: *${target.text.take(80)}*")
            }
        }

        // Coordinate fallback — try positions down the results list
        // CRITICAL: avoid bottom 16% of screen (nav bar area — Home/Shorts/Library tabs)
        val safeH = (h * 0.83f).toInt() // never tap below this
        val tapYPositions = listOf(0.28f, 0.36f, 0.44f, 0.52f, 0.60f)
        for (yFrac in tapYPositions) {
            val tapY = (h * yFrac).coerceAtMost(safeH.toFloat())
            runner.tapAtPoint(w / 2f, tapY)
            delay(1800)
            val after = runner.readScreen()

            // Success: we're on a video or channel page
            if (after.contains("Comments", ignoreCase = true)
                || after.contains("Like", ignoreCase = true)
                || isChannelPage(after)) {
                return SkillResult.Success("▶️ Playing video for: *$searchQuery*")
            }

            // Detect if we accidentally navigated to YouTube home (bottom nav tap)
            // Signs: "Recommended", "What to watch", no search query text visible
            val wentHome = !after.contains(searchQuery.take(5), ignoreCase = true)
                && (after.contains("Recommended", ignoreCase = true)
                    || after.contains("What to watch", ignoreCase = true)
                    || after.contains("Trending", ignoreCase = true))

            if (wentHome) {
                // Accidentally hit YouTube home tab — re-search WITHOUT recursing into playVideo()
                // (recursive playVideo() was causing the infinite loop: home→search→home→search...)
                if (retryCount >= 1) {
                    // Already retried once — give up gracefully
                    return SkillResult.Success("Searched YouTube for *$searchQuery* — tap a result to play.")
                }
                // Re-open search directly from YouTube home (no back, no home press)
                tapSearchIcon(runner)
                delay(500)
                runner.clearField()
                runner.typeReliably(searchQuery)
                delay(300)
                runner.pressEnter()
                delay(2200)
                dismissYouTubePopups(runner)
                delay(300)
                // Try tapping a video one more time (retryCount=1 prevents further recursion)
                return tapFirstVideo(runner, agent, searchQuery, retryCount = 1)
            }

            // Navigated somewhere wrong (not results, not video) — go back to results
            if (!after.contains(searchQuery.take(5), ignoreCase = true)) {
                runner.pressBack()
                delay(600)
            }
        }

        return SkillResult.Success("Searched YouTube for *$searchQuery* — tap a result to play.")
    }

    // ── Dismiss YouTube-specific popups WITHOUT going to home ─────────────────
    private suspend fun dismissYouTubePopups(runner: SandboxedRunner) {
        val bannersToTap = listOf(
            "Got it", "No thanks", "Dismiss", "Not now", "Skip",
            "Turn on watch history", "Turn on search history",
            "Watch history is off", "Search history is off",
            "Allow", "Continue", "Maybe later",
        )
        for (banner in bannersToTap) {
            if (runner.screenContains(banner)) {
                runner.tapByText(banner)
                delay(300)
                break // dismiss one at a time, re-check next step
            }
        }
    }

    // ── Tap a bottom nav tab by label ─────────────────────────────────────────
    private suspend fun tapBottomNav(runner: SandboxedRunner, label: String) {
        val tapped = runner.tapByText(label)
        if (!tapped) {
            val el = runner.getClickableElements().firstOrNull { e ->
                e.text.contains(label, ignoreCase = true) && e.centerY > runner.getScreenSize().second * 0.85f
            }
            if (el != null) runner.tapAtPoint(el.centerX.toFloat(), el.centerY.toFloat())
        }
    }

    // ── Extract clean search term from natural language goal ─────────────────
    // "go to MrBeast channel" → "MrBeast"
    // "navigate to Kurzgesagt" → "Kurzgesagt"
    // "play Tum Hi Ho" → "Tum Hi Ho"
    // "subscribe to Veritasium channel" → "Veritasium"
    private fun extractSearchTerm(goal: String, query: String): String {
        if (query.isNotBlank()) return query
        return goal
            .replace(Regex("""(?i)^(go to|open|navigate to|visit|take me to|find|show me|search for)\s+"""), "")
            .replace(Regex("""(?i)\s+(channel|page|on youtube)$"""), "")
            .trim()
            .take(60)
            .ifBlank { goal.take(60) }
    }

    // ── Detect if we're on a YouTube channel page ─────────────────────────────
    // Channel pages show subscriber count ("subscribers") + tab row (Videos/Shorts/Playlists)
    private fun isChannelPage(screen: String): Boolean {
        val lower = screen.lowercase()
        return lower.contains("subscribers") ||
            (lower.contains("videos") && lower.contains("shorts") && lower.contains("playlists"))
    }

    // ── Build a precise goal string — do EXACTLY what was asked, nothing more ──
    private fun buildFullGoal(goal: String, query: String): String {
        val searchTerm = extractSearchTerm(goal, query)

        // Detect if this is a channel navigation goal
        val isChannelGoal = goal.lowercase().let { g ->
            g.contains("channel") || g.contains("go to") || g.contains("navigate to") ||
            g.contains("subscribe") || g.contains("unsubscribe") || g.contains("visit")
        }

        return buildString {
            appendLine("You are in the YouTube app. The user said: \"$goal\"")
            appendLine()
            appendLine("DO EXACTLY what the user asked. NOTHING MORE. NOTHING LESS.")
            appendLine()
            appendLine("STEPS:")
            appendLine("1. Tap the Search icon (magnifying glass at top-right) — NOT the mic icon")
            appendLine("2. Type \"$searchTerm\" in the search field (text only, NOT voice)")
            appendLine("3. Press Enter — wait for results")
            appendLine("4. Dismiss banners (\"Got it\", \"No thanks\") if they appear")
            if (isChannelGoal) {
                appendLine("5. FIND THE CHANNEL in results:")
                appendLine("   - Look for a result that has a ROUND AVATAR + channel name + subscriber count")
                appendLine("   - If you see filter chips at the top of results, tap \"Channels\" to filter")
                appendLine("   - Tap the channel name/avatar to open the channel page")
                appendLine("6. SUCCESS — you are DONE when you see the channel page:")
                appendLine("   - Channel name as a heading at top")
                appendLine("   - Subscriber count (e.g. \"12M subscribers\")")
                appendLine("   - Tab row with Videos / Shorts / Playlists")
                appendLine("   - Call done IMMEDIATELY when you reach the channel page")
            } else {
                appendLine("5. Do exactly what the goal says — tap the right video/channel/button")
                appendLine("6. SUCCESS — call done when you reach the target (video playing or action done)")
            }
            appendLine()
            appendLine("STRICT RULES:")
            appendLine("- NEVER press back — scroll or tap visible elements instead")
            appendLine("- NEVER tap Home/Shorts/Library/Subscriptions bottom tabs")
            appendLine("- NEVER tap the mic/voice/camera icon — text search ONLY")
            appendLine("- Do NOT add extra actions (don't like if asked to subscribe)")
            appendLine("- Call done IMMEDIATELY when goal is achieved — do NOT keep navigating")
        }.trimEnd()
    }
}
