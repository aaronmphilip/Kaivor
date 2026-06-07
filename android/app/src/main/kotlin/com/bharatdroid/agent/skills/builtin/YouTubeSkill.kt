package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.ScreenAgent
import com.bharatdroid.agent.ScreenElement
import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class YouTubeSkill : Skill {

    override val manifest = SkillManifest(
        id = "youtube",
        name = "YouTube",
        version = "9.0.0",
        description = "Play videos, search, go to channels, share video links, skip ads — any YouTube task",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK, Permission.CLIPBOARD,
        ),
        allowedPackages = setOf("com.google.android.youtube", "com.whatsapp"),
        exampleParamsHint = """{"action": "play", "query": "Arijit Singh Tum Hi Ho"}""",
        uiKnowledge = """
YouTube home screen has a search icon (magnifying glass) at top-right. Tapping it opens a search field. After searching, results appear as a scrollable list — each result shows a thumbnail, title, channel name, view count, and duration. Full videos show duration like '4:22' in thumbnail corner. Shorts show a vertical phone icon or say 'Shorts'. Channel results show a round avatar with subscriber count. The bottom navigation has: Home, Shorts, +, Subscriptions, Library tabs — NEVER tap these during a search task.

AD DETECTION: When a YouTube ad plays before a video, the screen shows 'Skip Ad', 'Skip Ads', 'Ad ·', 'Ad 1 of 2', 'Visit advertiser', or 'Your video will play after the ad'. If 'Skip Ad' / 'Skip Ads' button is visible, tap it immediately. If not visible yet, wait — it appears after 5 seconds. Non-skippable ads must be waited out (usually 15-30 seconds).

SHARE LINK: The Share button is in the action row BELOW the video player (same row as Like, Dislike). Tap Share → a bottom sheet opens. Tap 'Copy link' in that sheet. The YouTube URL is now in the clipboard.
""".trimIndent(),
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase() ?: "play"
        val query = params["query"] as? String ?: ""
        val goal = params["goal"] as? String ?: ""
        val contact = params["contact"] as? String ?: params["to"] as? String ?: ""

        runner.openApp("com.google.android.youtube")
        runner.waitForApp("com.google.android.youtube", timeoutMs = 6000)
        delay(200)
        dismissYouTubePopups(runner)
        delay(100)

        return when (action) {

            "play", "search", "lyrics" -> {
                if (query.isBlank()) return SkillResult.Failure("What do you want to play or search on YouTube?")

                val searchQuery = when {
                    action == "lyrics" && !query.contains("lyrics", ignoreCase = true) -> "$query lyrics"
                    else -> query
                }
                playVideo(runner, agent, searchQuery)
            }

            "share", "share_link", "copy_link", "get_link" -> {
                if (query.isBlank()) return SkillResult.Failure("Which video do you want to share the link for?")
                shareVideo(runner, agent, query, contact.takeIf { it.isNotBlank() })
            }

            "skip_ads", "skip_ad", "watch", "wait_ads" -> {
                // Skip/wait for ads on whatever is currently playing
                val hadAd = waitForAdsToFinish(runner, maxWaitMs = 60_000)
                if (hadAd) SkillResult.Success("✅ Ads done — video is playing.")
                else SkillResult.Success("No ads detected — video is already playing.")
            }

            "subscriptions", "subs" -> {
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
                val rawGoal = goal.ifBlank { query }
                val fullGoal = buildFullGoal(rawGoal, query)
                val result = agent.executeGoal(runner, fullGoal, maxSteps = 80)
                SkillResult.Success(result)
            }

            else -> {
                val fallback = goal.ifBlank { query.ifBlank { action } }
                val fullGoal = buildFullGoal(fallback, query)
                val result = agent.executeGoal(runner, fullGoal, maxSteps = 70)
                SkillResult.Success(result)
            }
        }
    }

    // ── Share a video: search → play → copy link → optionally WhatsApp ─────────
    private suspend fun shareVideo(
        runner: SandboxedRunner,
        agent: ScreenAgent,
        query: String,
        contact: String?,
    ): SkillResult {
        // Step 1: Find and play the video
        val playResult = playVideo(runner, agent, query)
        if (playResult is SkillResult.Failure) return playResult

        // Step 2: Wait for ads to finish so Share button is accessible
        waitForAdsToFinish(runner, maxWaitMs = 60_000)
        delay(600)

        // Step 3: Use the agent to tap Share → Copy link
        val copyGoal = """
You are watching a YouTube video. Get the video link by following these steps:

1. Find the Share button in the action row BELOW the video player.
   - It is in the same row as Like and Dislike buttons.
   - It has an arrow/forward icon and the text "Share".
   - If the action row is not visible, scroll up slightly.
2. Tap the Share button.
   → A bottom sheet appears with app icons and a "Copy link" option.
3. Tap "Copy link" (NOT any app icon like WhatsApp/Telegram — ONLY "Copy link").
   → The YouTube link is now copied to the clipboard.
4. Say "LINK COPIED" when done.

STRICT RULES:
- Tap ONLY "Copy link" in the share sheet — do not tap WhatsApp, Telegram, Instagram, or any other app icon.
- If the Share button is behind the video controls, tap the video once to reveal controls, then tap Share.
- Do NOT go back or navigate away from the video.
""".trimIndent()

        agent.executeGoal(runner, copyGoal, maxSteps = 35)
        delay(500)

        // Step 4: Read the clipboard
        val link = runner.readClipboard()
        val isYouTubeLink = link.contains("youtu.be", ignoreCase = true)
            || link.contains("youtube.com/watch", ignoreCase = true)
            || link.contains("youtube.com/shorts", ignoreCase = true)

        if (link.isBlank() || !isYouTubeLink) {
            return SkillResult.Failure(
                "Couldn't read the YouTube link from clipboard (got: \"${link.take(80)}\"). " +
                "The video is playing — you can manually tap Share → Copy link."
            )
        }

        // Step 5: If contact specified, send via WhatsApp
        if (!contact.isNullOrBlank()) {
            return sendLinkViaWhatsApp(runner, agent, link, contact, query)
        }

        return SkillResult.Success("🔗 YouTube link:\n$link")
    }

    // ── Send a link via WhatsApp to a contact ─────────────────────────────────
    private suspend fun sendLinkViaWhatsApp(
        runner: SandboxedRunner,
        agent: ScreenAgent,
        link: String,
        contact: String,
        videoTitle: String,
    ): SkillResult {
        runner.openApp("com.whatsapp")
        runner.waitForApp("com.whatsapp", timeoutMs = 6000)
        delay(600)
        runner.dismissPopups(1)
        delay(300)

        val sendGoal = """
You are in WhatsApp. Send the link "$link" to "$contact".

STEPS:
1. Tap the search icon (magnifying glass) at the top of WhatsApp.
2. Type "$contact" in the search field.
3. Wait for results. Tap the contact row that shows "$contact".
   - Do NOT tap the small round avatar on the far left.
   - Tap the contact name text in the middle of the result row.
4. The chat opens. Tap the message input field at the BOTTOM of the screen.
5. Type this exact text: $link
6. Tap the Send button (green arrow on the right of the message field).
7. Confirm the message appears in the chat as a sent bubble.

STRICT RULES:
- Send ONLY the link text above — no extra words before or after.
- Do NOT tap any message bubble in the chat history.
- Do NOT tap voice note / camera / attachment icons.
- Stop as soon as the link is sent.
""".trimIndent()

        agent.executeGoal(runner, sendGoal, maxSteps = 55)

        return SkillResult.Success("🔗 Sent YouTube link to *$contact*:\n$link\n_(video: $videoTitle)_")
    }

    // ── Smart ad detection + skip/wait ─────────────────────────────────────────
    // Returns true if ads were present (and are now done), false if no ad detected.
    private suspend fun waitForAdsToFinish(
        runner: SandboxedRunner,
        maxWaitMs: Long = 60_000,
    ): Boolean {
        // Ad presence signals — any of these means an ad is playing
        val adSignals = listOf(
            "Skip Ad", "Skip Ads", "skip ad", "skip ads",
            "Ad ·", "Ad·", "·Ad",
            "Ad 1 of", "Ad 2 of",
            "Visit advertiser",
            "Why this ad",
            "Stop seeing this ad",
            "Your video will play after",
            "Your video will play in",
        )
        // Texts for the skippable-ad Skip button (appears after ~5 s)
        val skipButtonTexts = listOf("Skip Ad", "Skip Ads", "Skip ad", "Skip ads", "SKIP AD", "SKIP ADS", "Skip")

        // Quick first-check — if no ad is playing right now, bail immediately
        val firstScreen = runner.readScreen()
        val hadAdAtStart = adSignals.any { firstScreen.contains(it, ignoreCase = true) }
        if (!hadAdAtStart) return false

        val deadline = System.currentTimeMillis() + maxWaitMs
        var adEverDetected = true // we already confirmed above

        while (System.currentTimeMillis() < deadline) {
            val screen = runner.readScreen()
            val adVisible = adSignals.any { screen.contains(it, ignoreCase = true) }

            if (!adVisible) {
                // Ad finished (or was skipped)
                delay(300) // brief settle
                return true
            }

            // ── Try to tap Skip button (appears only on skippable ads after 5s) ──
            // First try by element — more reliable than text-match alone
            val elements = runner.getClickableElements()
            val skipEl = elements.firstOrNull { el ->
                val combined = (el.text + " " + el.contentDescription).lowercase()
                // Must contain "skip" and be small (not a big content block)
                combined.contains("skip") && el.text.length < 20
            }
            if (skipEl != null) {
                runner.tapAtPoint(skipEl.centerX.toFloat(), skipEl.centerY.toFloat())
                delay(800)
                // Verify ad is gone
                val afterSkip = runner.readScreen()
                val stillHasAd = adSignals.any { afterSkip.contains(it, ignoreCase = true) }
                if (!stillHasAd) return true
                // If ad indicator still there, continue waiting (might be 2nd ad)
                delay(1000)
                continue
            }

            // Fallback: text-based tap for Skip
            val skipped = skipButtonTexts.any { runner.tapByText(it) }
            if (skipped) {
                delay(1000)
                continue
            }

            // No skip button yet — non-skippable ad or skip not available yet
            // Wait and try again
            delay(1500)
        }

        return adEverDetected
    }

    // ── Open search, type query, wait for results, tap first real video ───────
    private suspend fun playVideo(
        runner: SandboxedRunner,
        agent: ScreenAgent,
        searchQuery: String,
    ): SkillResult {
        val screen0 = runner.readScreen()
        val alreadyInSearch = screen0.contains("Search YouTube", ignoreCase = true)
        val alreadyPlaying = screen0.contains("Subscribe", ignoreCase = true)
            && (screen0.contains("Comments", ignoreCase = true) || screen0.contains("Like", ignoreCase = true))

        if (alreadyPlaying) {
            runner.pressBack()
            delay(600)
            val nowScreen = runner.readScreen()
            if (nowScreen.contains("Subscribe", ignoreCase = true)
                && nowScreen.contains("Comments", ignoreCase = true)) {
                runner.pressBack()
                delay(500)
            }
        }

        if (!alreadyInSearch) {
            val searchIconTapped = tapSearchIcon(runner)
            if (!searchIconTapped) {
                val (_, sH) = runner.getScreenSize()
                val el = runner.getClickableElements().firstOrNull { e ->
                    val t = (e.text + e.hint + e.contentDescription).lowercase()
                    t.contains("search") && !t.contains("youtube") && e.centerY < sH * 0.12f
                }
                if (el != null) runner.tapAtPoint(el.centerX.toFloat(), el.centerY.toFloat())
            }
            delay(600)
        }

        runner.clearField()
        delay(100)
        val typed = runner.typeInFieldWithHint("Search YouTube", searchQuery)
            || runner.typeInFieldWithHint("Search", searchQuery)
            || runner.typeReliably(searchQuery)

        if (!typed) return SkillResult.Failure("Could not type in YouTube search field")
        delay(300)
        runner.pressEnter()
        delay(2000)

        dismissYouTubePopups(runner)
        delay(400)

        val wantShorts = searchQuery.contains("shorts", ignoreCase = true)
        val filterChipTapped = if (wantShorts) {
            runner.tapByText("Shorts")
        } else {
            runner.tapByText("Videos")
        }
        if (filterChipTapped) {
            delay(1000)
            dismissYouTubePopups(runner)
            delay(300)
        }

        return tapFirstVideo(runner, agent, searchQuery)
    }

    // ── Tap the search icon ───────────────────────────────────────────────────
    private suspend fun tapSearchIcon(runner: SandboxedRunner): Boolean {
        val (screenW, screenH) = runner.getScreenSize()
        val elements = runner.getClickableElements()
        val searchEl = elements.firstOrNull { el ->
            val combined = (el.text + el.hint + el.contentDescription + el.viewId).lowercase()
            combined.contains("search") && el.centerY < screenH * 0.12f && el.width < screenW * 0.2f
        }
        if (searchEl != null) {
            return runner.tapAtPoint(searchEl.centerX.toFloat(), searchEl.centerY.toFloat())
        }
        return runner.tapByText("Search")
    }

    // ── Tap first real video result ───────────────────────────────────────────
    private suspend fun tapFirstVideo(
        runner: SandboxedRunner,
        agent: ScreenAgent,
        searchQuery: String,
        retryCount: Int = 0,
    ): SkillResult {
        val (w, h) = runner.getScreenSize()
        val elements = runner.getClickableElements()

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
            val wantShorts = searchQuery.contains("shorts", ignoreCase = true)
            val (screenW, _) = runner.getScreenSize()
            val target = if (wantShorts) {
                videoEls.firstOrNull { it.width < screenW * 0.6f } ?: videoEls.first()
            } else {
                videoEls.firstOrNull { it.width > screenW * 0.7f } ?: videoEls.first()
            }
            val ok = runner.tapAtPoint(target.centerX.toFloat(), target.centerY.toFloat())
            if (ok) {
                delay(2000)

                // ── Wait for any pre-roll ad to finish before confirming success ──
                waitForAdsToFinish(runner, maxWaitMs = 60_000)
                delay(300)

                val afterScreen = runner.readScreen()
                val isVideoPlaying = afterScreen.contains("Comments", ignoreCase = true)
                    || afterScreen.contains("Like", ignoreCase = true)
                val isOnChannel = isChannelPage(afterScreen)
                if (isVideoPlaying) {
                    return SkillResult.Success("▶️ Playing: *${target.text.take(80)}*")
                }
                if (isOnChannel) {
                    return SkillResult.Success("📺 Opened channel: *${target.text.take(80)}*")
                }
                return SkillResult.Success("Opened: *${target.text.take(80)}*")
            }
        }

        val safeH = (h * 0.83f).toInt()
        val tapYPositions = listOf(0.28f, 0.36f, 0.44f, 0.52f, 0.60f)
        for (yFrac in tapYPositions) {
            val tapY = (h * yFrac).coerceAtMost(safeH.toFloat())
            runner.tapAtPoint(w / 2f, tapY)
            delay(1800)

            // Wait for any ad before checking success
            waitForAdsToFinish(runner, maxWaitMs = 60_000)
            delay(300)

            val after = runner.readScreen()
            if (after.contains("Comments", ignoreCase = true)
                || after.contains("Like", ignoreCase = true)
                || isChannelPage(after)) {
                return SkillResult.Success("▶️ Playing video for: *$searchQuery*")
            }

            val wentHome = !after.contains(searchQuery.take(5), ignoreCase = true)
                && (after.contains("Recommended", ignoreCase = true)
                    || after.contains("What to watch", ignoreCase = true)
                    || after.contains("Trending", ignoreCase = true))

            if (wentHome) {
                if (retryCount >= 1) {
                    return SkillResult.Success("Searched YouTube for *$searchQuery* — tap a result to play.")
                }
                tapSearchIcon(runner)
                delay(500)
                runner.clearField()
                runner.typeReliably(searchQuery)
                delay(300)
                runner.pressEnter()
                delay(2200)
                dismissYouTubePopups(runner)
                delay(300)
                return tapFirstVideo(runner, agent, searchQuery, retryCount = 1)
            }

            if (!after.contains(searchQuery.take(5), ignoreCase = true)) {
                runner.pressBack()
                delay(600)
            }
        }

        return SkillResult.Success("Searched YouTube for *$searchQuery* — tap a result to play.")
    }

    // ── Dismiss YouTube-specific popups ───────────────────────────────────────
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
                break
            }
        }
    }

    // ── Tap a bottom nav tab ──────────────────────────────────────────────────
    private suspend fun tapBottomNav(runner: SandboxedRunner, label: String) {
        val tapped = runner.tapByText(label)
        if (!tapped) {
            val el = runner.getClickableElements().firstOrNull { e ->
                e.text.contains(label, ignoreCase = true) && e.centerY > runner.getScreenSize().second * 0.85f
            }
            if (el != null) runner.tapAtPoint(el.centerX.toFloat(), el.centerY.toFloat())
        }
    }

    // ── Extract clean search term from natural language ───────────────────────
    private fun extractSearchTerm(goal: String, query: String): String {
        if (query.isNotBlank()) return query
        return goal
            .replace(Regex("""(?i)^(go to|open|navigate to|visit|take me to|find|show me|search for)\s+"""), "")
            .replace(Regex("""(?i)\s+(channel|page|on youtube)$"""), "")
            .trim()
            .take(60)
            .ifBlank { goal.take(60) }
    }

    // ── Detect YouTube channel page ───────────────────────────────────────────
    private fun isChannelPage(screen: String): Boolean {
        val lower = screen.lowercase()
        return lower.contains("subscribers") ||
            (lower.contains("videos") && lower.contains("shorts") && lower.contains("playlists"))
    }

    // ── Build a precise goal string ───────────────────────────────────────────
    private fun buildFullGoal(goal: String, query: String): String {
        val searchTerm = extractSearchTerm(goal, query)

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
                appendLine("6. If an ad plays after tapping a video:")
                appendLine("   - If 'Skip Ad' button appears → tap it immediately")
                appendLine("   - If no skip button → wait for the ad to finish (do NOT go back)")
                appendLine("7. SUCCESS — call done when you reach the target (video playing or action done)")
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
