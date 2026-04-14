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
                val fullGoal = buildFullGoal(goal.ifBlank { query }, query)
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
                val el = runner.getClickableElements().firstOrNull { e ->
                    val t = (e.text + e.hint + e.contentDescription).lowercase()
                    t.contains("search") && !t.contains("youtube") && e.centerY < 400
                }
                if (el != null) runner.tapAtPoint(el.centerX.toFloat(), el.centerY.toFloat())
            }
            delay(600)
        }

        // Step 2: Type query
        runner.clearFocusedField()
        delay(100)
        val typed = runner.typeInFocused(searchQuery)
            || runner.typeInFieldWithHint("Search YouTube", searchQuery)
            || runner.typeInFieldWithHint("Search", searchQuery)

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
        // Try elements first
        val elements = runner.getClickableElements()
        val searchEl = elements.firstOrNull { el ->
            val combined = (el.text + el.hint + el.contentDescription + el.viewId).lowercase()
            combined.contains("search") && el.centerY < 350 && el.width < 200
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
                val isPlaying = afterScreen.contains("Subscribe", ignoreCase = true)
                    || afterScreen.contains("Comments", ignoreCase = true)
                    || afterScreen.contains("Like", ignoreCase = true)
                if (isPlaying) {
                    return SkillResult.Success("▶️ Playing: *${target.text.take(80)}*")
                }
                // If tap opened something else (channel page etc) that's fine too
                return SkillResult.Success("Opened: *${target.text.take(80)}*")
            }
        }

        // Coordinate fallback — try positions down the results list
        // CRITICAL: avoid bottom 15% of screen (nav bar area — Home/Shorts/Library tabs)
        val safeH = (h * 0.84f).toInt() // never tap below this
        val tapYPositions = listOf(0.28f, 0.36f, 0.44f, 0.52f, 0.60f)
        for (yFrac in tapYPositions) {
            val tapY = (h * yFrac).coerceAtMost(safeH.toFloat())
            runner.tapAtPoint(w / 2f, tapY)
            delay(1800)
            val after = runner.readScreen()

            // Success: we're on a video/channel page
            if (after.contains("Subscribe", ignoreCase = true)
                || after.contains("Comments", ignoreCase = true)
                || after.contains("Like", ignoreCase = true)) {
                return SkillResult.Success("▶️ Playing video for: *$searchQuery*")
            }

            // Detect if we accidentally navigated to YouTube home (bottom nav tap)
            // Signs: "Recommended", "What to watch", no search query text visible
            val wentHome = !after.contains(searchQuery.take(5), ignoreCase = true)
                && (after.contains("Recommended", ignoreCase = true)
                    || after.contains("What to watch", ignoreCase = true)
                    || after.contains("Trending", ignoreCase = true))

            if (wentHome) {
                // Re-do search from scratch instead of pressing back (which would exit YouTube)
                return playVideo(runner, agent, searchQuery)
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

    // ── Build a rich goal string for complex tasks ────────────────────────────
    private fun buildFullGoal(goal: String, query: String): String {
        val searchTerm = query.ifBlank {
            // Extract key noun from goal — skip filler words
            val skip = setOf("go", "to", "the", "and", "then", "open", "find",
                "channel", "subscribe", "like", "video", "videos", "on", "in", "their", "its")
            goal.split(" ").filter { w -> w.length > 2 && w.lowercase() !in skip }
                .take(2).joinToString(" ").ifBlank { goal.take(40) }
        }
        return """
You are in the YouTube app. Complete this task: $goal

EXECUTION PLAN:
1. Tap the Search icon (magnifying glass) at the top right — NOT the back button
2. Type "$searchTerm" into the search field
3. Press Enter and wait 2 seconds for results
4. Dismiss any history/privacy banners by tapping "Got it" or "No thanks"
5. Scroll through results to find: ${goal.take(60)}
6. Tap the correct video or channel
7. Complete the requested action (like, subscribe, watch, etc.)

CRITICAL RULES:
- NEVER press the back button to navigate — use scroll instead
- NEVER tap the Home tab unless explicitly told to go home
- After search results appear, SCROLL DOWN to see more — don't go back
- To subscribe: tap channel name → tap SUBSCRIBE button
- To like: tap the thumbs up icon below the video
- When done, report what you accomplished
        """.trimIndent()
    }
}
