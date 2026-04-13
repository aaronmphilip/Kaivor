package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class InstagramSkill : Skill {

    override val manifest = SkillManifest(
        id = "instagram",
        name = "Instagram",
        version = "6.0.0",
        description = "Search profiles, browse reels, send DMs, follow, like, post — any Instagram task",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK,
        ),
        allowedPackages = setOf("com.instagram.android"),
        exampleParamsHint = """{"action": "search", "query": "virat kohli"}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase() ?: "search"
        val query = params["query"] as? String ?: params["contact"] as? String ?: ""
        val message = params["message"] as? String ?: ""

        runner.openApp("com.instagram.android")
        runner.waitForApp("com.instagram.android", timeoutMs = 6000)
        delay(800)
        runner.dismissPopups(2)
        delay(200)

        val goal = when (action) {
            "search" ->
                """You are in Instagram. Search for "$query".
                STEPS: 1) Tap the Search icon (magnifying glass) in the bottom navigation bar. 2) Tap the search text field at the top of the search page. 3) Type "$query". 4) Wait for results. 5) Tap the most relevant profile or result."""

            "reels" ->
                "You are in Instagram. Open the Reels tab from the bottom navigation bar."

            "home" ->
                "You are in Instagram. Go to the Home feed by tapping the Home icon in the bottom navigation bar."

            "dm" ->
                """You are in Instagram. Send a DM to "$query" saying "$message".
                STEPS: 1) Tap the paper airplane / Direct Messages icon at the top right. 2) Tap the compose/search button. 3) Type "$query". 4) Tap the contact result. 5) Tap the message input. 6) Type "$message". 7) Tap Send."""

            else ->
                // Any task: follow, like, comment, post, story, etc.
                params["goal"] as? String
                    ?: "Do this on Instagram: $action ${query.ifBlank { "" }} ${message.ifBlank { "" }}".trim()
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 20)
        return SkillResult.Success(result)
    }
}
