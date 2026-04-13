package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.ScreenAgent
import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class YouTubeSkill : Skill {

    override val manifest = SkillManifest(
        id = "youtube",
        name = "YouTube Search & Play",
        version = "6.0.0",
        description = "Play videos, search, subscribe, navigate tabs, and do any multi-step YouTube task",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK,
        ),
        allowedPackages = setOf("com.google.android.youtube"),
        exampleParamsHint = """{"query": "Arijit Singh latest song", "action": "play"}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent
        val action = (params["action"] as? String)?.lowercase() ?: "play"
        val query = params["query"] as? String ?: ""
        val goal = params["goal"] as? String ?: ""

        // ── Open YouTube ─────────────────────────────────────────────────────
        runner.openApp("com.google.android.youtube")
        runner.waitForApp("com.google.android.youtube", timeoutMs = 5000)
        delay(800)

        // ── Route by action ──────────────────────────────────────────────────
        return when (action) {
            // ── PLAY / LYRICS: search and tap first result ───────────────────
            "play", "lyrics" -> {
                if (query.isBlank()) return SkillResult.Failure("What do you want to play on YouTube?")
                val searchQuery = if (action == "lyrics" && !query.contains("lyrics", ignoreCase = true))
                    "$query lyrics" else query
                searchAndPlay(runner, agent, searchQuery)
            }

            // ── SUBSCRIBE tab or channel navigation ──────────────────────────
            "subscriptions" -> {
                tapBottomTab(runner, agent, "Subscriptions")
                delay(600)
                val g = goal.ifBlank { "Navigate to Subscriptions tab on YouTube and show what's there" }
                if (agent != null) agent.executeGoal(runner, g, maxSteps = 20)
                SkillResult.Success("Opened YouTube Subscriptions.")
            }

            // ── GOAL: multi-step AI-driven task (subscribe to channels, etc.) ─
            "goal" -> {
                if (goal.isBlank() && query.isBlank())
                    return SkillResult.Failure("Provide a 'goal' param describing what to do on YouTube.")
                
                // Extract search term from goal by filtering out common words
                // Example: "Go to ValueEntainment go to their channel..." → search for "ValueEntainment"
                val searchTerm = query.ifBlank {
                    val filterWords = setOf("go", "to", "the", "and", "then", "like", "channel", "subscribe", "channels", "videos", "video", "in", "on", "top", "popular", "their")
                    val words = goal.split(" ").filter { word ->
                        word.length > 2 && !filterWords.contains(word.lowercase())
                    }
                    words.take(1).joinToString(" ").ifBlank { goal.take(40) }
                }
                
                val fullGoal = buildString {
                    append("TASK: ")
                    append(goal)
                    append("\n\n")
                    append("REQUIRED ACTIONS:\n")
                    append("1. Find and tap the search icon on YouTube\n")
                    append("2. Type \"$searchTerm\" to search\n")
                    append("3. Navigate and find what user requested\n")
                    append("4. Execute the user's goal: $goal\n")
                    append("5. Scroll and navigate using the YouTube interface\n\n")
                    append("IMPORTANT RULES:\n")
                    append("- Search for: $searchTerm\n")
                    append("- User wants: $goal\n")
                    append("- Use SCROLL to navigate between results, NOT back button\n")
                    append("- When you find the right item/channel, tap it and complete the action\n")
                    append("- When complete, provide a success message about what was done\n")
                }
                if (agent == null) return SkillResult.Failure("Agent not available.")
                val result = agent.executeGoal(runner, fullGoal, maxSteps = 40)
                SkillResult.Success(result)
            }

            // ── HOME: go to YouTube home tab ─────────────────────────────────
            "home" -> {
                tapBottomTab(runner, agent, "Home")
                delay(400)
                SkillResult.Success("Opened YouTube Home.")
            }

            // ── SEARCH: search without playing ───────────────────────────────
            "search" -> {
                if (query.isBlank()) return SkillResult.Failure("What should I search for?")
                openSearch(runner, agent)
                typeAndSearch(runner, query)
                SkillResult.Success("Searched YouTube for: $query")
            }

            // ── Unknown → try as a goal ──────────────────────────────────────
            else -> {
                val fallbackGoal = goal.ifBlank { query.ifBlank { action } }
                if (agent != null && fallbackGoal.isNotBlank()) {
                    val result = agent.executeGoal(runner, fallbackGoal, maxSteps = 20)
                    SkillResult.Success(result)
                } else {
                    SkillResult.Failure("Unknown YouTube action '$action'. Try: play, search, subscriptions, goal.")
                }
            }
        }
    }

    // ── Tap a bottom navigation tab by label ──────────────────────────────────
    private suspend fun tapBottomTab(runner: SandboxedRunner, agent: ScreenAgent?, label: String) {
        val tapped = runner.tapByText(label)
            || run {
                val el = runner.getClickableElements().firstOrNull { el ->
                    el.text.contains(label, ignoreCase = true)
                }
                if (el != null) runner.tapAtPoint(el.centerX.toFloat(), el.centerY.toFloat()) else false
            }
            || agent?.tapSmartly(runner, "Tap the '$label' tab in the YouTube bottom navigation bar") == true
    }

    // ── Open the YouTube search bar ───────────────────────────────────────────
    private suspend fun openSearch(runner: SandboxedRunner, agent: ScreenAgent?) {
        val screen = runner.readScreen()
        val inSearch = screen.contains("Search YouTube", ignoreCase = true)
        if (inSearch) return

        val tapped = runner.tapByText("Search")
            || run { val n = runner.findByDescription("Search"); if (n != null) runner.tap(n) else false }
            || agent?.tapSmartly(runner, "Tap the Search icon (magnifying glass) at top of YouTube") == true
        if (tapped) {
            runner.waitForText("Search YouTube", timeoutMs = 2000)
            delay(200)
        }
    }

    // ── Type a query and submit search ────────────────────────────────────────
    private suspend fun typeAndSearch(runner: SandboxedRunner, query: String) {
        runner.clearFocusedField()
        delay(100)
        runner.typeInFocused(query)
            || runner.typeInFieldWithHint("Search YouTube", query)
            || runner.typeInFieldWithHint("Search", query)
        delay(200)
        runner.pressEnter()
        delay(1500) // wait for results to load
    }

    // ── Search + play first real video result ─────────────────────────────────
    private suspend fun searchAndPlay(
        runner: SandboxedRunner,
        agent: ScreenAgent?,
        searchQuery: String,
    ): SkillResult {
        val screen0 = runner.readScreen()
        val inVideo = screen0.contains("Subscribe", ignoreCase = true) && screen0.contains("Comments", ignoreCase = true)
        val inSearch = screen0.contains("Search YouTube", ignoreCase = true)

        when {
            inVideo -> { runner.pressBack(); delay(500) }
            inSearch -> {
                runner.clearFocusedField(); delay(150)
                val typed = runner.typeInFocused(searchQuery)
                    || runner.typeInFieldWithHint("Search YouTube", searchQuery)
                if (typed) {
                    runner.pressEnter(); delay(1500)
                    return tapFirstVideoResult(runner, agent, searchQuery)
                }
            }
        }

        runner.dismissPopups(2)
        delay(200)

        openSearch(runner, agent)
        typeAndSearch(runner, searchQuery)
        return tapFirstVideoResult(runner, agent, searchQuery)
    }

    // ── Tap the first real video result (skip banners, filter buttons) ────────
    private suspend fun tapFirstVideoResult(
        runner: SandboxedRunner,
        agent: ScreenAgent?,
        searchQuery: String,
    ): SkillResult {
        // Dismiss YouTube watch/search history banners
        val youtubeBanners = listOf(
            "Turn on watch history", "Turn on search history",
            "Watch history is off", "Search history is off",
            "Got it", "No thanks", "Dismiss",
        )
        for (banner in youtubeBanners) {
            if (runner.screenContains(banner)) {
                runner.tapByText(banner)
                delay(300)
                break
            }
        }
        delay(400)

        val (w, h) = runner.getScreenSize()

        // AI element pick with strict video-only filter
        if (agent != null) {
            val elements = runner.getClickableElements()
            val skipWords = setOf(
                "turn on", "history", "shorts", "filter", "all", "videos",
                "channels", "got it", "no thanks", "dismiss", "cancel",
                "search", "home", "subscriptions", "library", "notifications"
            )
            val videoElements = elements.filter { el ->
                val lowerText = el.text.lowercase()
                skipWords.none { skip -> lowerText.contains(skip) }
                    && el.text.length > 8
                    && el.isClickable
                    && el.centerY > h * 0.25
            }

            if (videoElements.isNotEmpty()) {
                val first = videoElements.first()
                val ok = runner.tapAtPoint(first.centerX.toFloat(), first.centerY.toFloat())
                if (ok) {
                    delay(1200)
                    val afterScreen = runner.readScreen()
                    val isPlaying = afterScreen.contains("Subscribe", ignoreCase = true)
                        || afterScreen.contains("Comments", ignoreCase = true)
                    if (isPlaying) return SkillResult.Success("▶️ Playing: *${first.text.take(60)}*")
                }
            }
        }

        // Coordinate fallback — scan down the results list
        val tapPositions = listOf(0.30f, 0.38f, 0.45f, 0.52f)
        for (yFraction in tapPositions) {
            runner.tapAtPoint(w / 2f, h * yFraction)
            delay(1000)
            val afterScreen = runner.readScreen()
            if (afterScreen.contains("Subscribe", ignoreCase = true)
                || afterScreen.contains("Comments", ignoreCase = true)
                || afterScreen.contains("Like", ignoreCase = true)) {
                return SkillResult.Success("▶️ Playing video for: *$searchQuery*")
            }
            runner.pressBack()
            delay(600)
        }

        return SkillResult.Success("Searched for *$searchQuery* on YouTube. Tap a video to play.")
    }
}
