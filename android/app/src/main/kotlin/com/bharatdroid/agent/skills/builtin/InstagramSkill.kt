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
        uiKnowledge = """
Instagram UI guide:
- Bottom navigation bar (left to right): Home (house), Search (magnifying glass), Reels (clapperboard), Shop (bag), Profile (person)
- Home feed: vertical scroll of posts; double-tap or tap heart to like; tap speech bubble to comment; tap paper airplane to share
- Story bar: row of circular profile pictures at the top of the home feed; tap a circle to view that user's story
- Post camera / Create: tap the + icon in the center of the bottom nav bar to create a new post, reel, or story
- Search screen: tap the magnifying glass icon; a search text field appears at the top; results show profiles, hashtags, places
- DMs (Direct Messages): tap the paper airplane icon at the top right of the home screen; shows conversation list
- Profile screen: tap the person icon (rightmost in bottom nav); shows your grid of posts, follower/following count, bio
- Reels tab: tap the clapperboard icon (third in bottom nav); full-screen vertical video feed
- Notification bell: top right of home screen next to DM icon; shows likes, comments, follows
""".trimIndent(),
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
