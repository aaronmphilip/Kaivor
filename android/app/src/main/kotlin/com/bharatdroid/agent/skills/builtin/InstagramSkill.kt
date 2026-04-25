package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class InstagramSkill : Skill {

    override val manifest = SkillManifest(
        id = "instagram",
        name = "Instagram",
        version = "7.0.0",
        description = "Search profiles, browse reels, send DMs, follow, like, comment, view stories — any Instagram task",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK,
        ),
        allowedPackages = setOf("com.instagram.android"),
        exampleParamsHint = """{"action": "dm", "contact": "virat.kohli", "message": "Hi!"}""",
        uiKnowledge = """
Instagram UI guide:
- Bottom navigation bar (left to right): Home (house), Search (magnifying glass), Reels (clapperboard/play), Shop (bag), Profile (person)
- Home feed: vertical scroll of posts; double-tap or tap heart to like; tap speech bubble to comment; tap paper airplane to share
- Story bar: row of circular profile pictures at the top of the home feed; tap a circle to view that user's story; tap left/right sides to go back/forward
- Post camera / Create: tap the + icon in the center of the bottom nav bar to create a new post, reel, or story
- Search screen: tap the magnifying glass (second icon in bottom nav); search field at the top; results show profiles, hashtags, places
- DMs (Direct Messages): tap the paper airplane icon at the top right of the home screen; shows conversation list; tap a conversation to open it; message input at the bottom
- Profile screen: tap the person icon (rightmost in bottom nav); shows your grid of posts, follower/following count, bio, Edit Profile button
- Follow button: blue "Follow" button on a profile page; turns to "Following" when followed
- Like button: heart icon below each post; turns red when liked
- Comment: speech bubble icon below post → opens comments; text field at the bottom to type
- Notifications: bell icon at the top right of home screen
""".trimIndent(),
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase() ?: "search"
        val query = params["query"] as? String ?: params["contact"] as? String
            ?: params["username"] as? String ?: params["profile"] as? String ?: ""
        val message = params["message"] as? String ?: params["comment"] as? String ?: ""

        runner.openApp("com.instagram.android")
        runner.waitForApp("com.instagram.android", timeoutMs = 7000)
        delay(800)
        runner.dismissPopups(2)
        delay(200)

        val goal = when (action) {
            "search", "find_profile" -> {
                if (query.isBlank()) return SkillResult.Failure("Who or what should I search for on Instagram?")
                // Pre-type into search
                val typed = runner.typeInFieldWithHint("Search", query)
                if (typed) delay(1000)
                val searchDone = typed
                """You are in Instagram. ${if (searchDone) "Search results for \"$query\" are showing." else "Search for \"$query\"."}
STEPS:
${if (!searchDone) "1. Tap the Search icon (magnifying glass) in the bottom navigation bar\n2. Tap the search text field at the top\n3. Type \"$query\"\n4. Wait for results" else "1. Look at the search results"}
${if (searchDone) "2." else "5."} Tap the most relevant profile or result (match the exact username if given)
${if (searchDone) "3." else "6."} Report: username, full name, follower/following count, bio"""
            }

            "follow" -> {
                if (query.isBlank()) return SkillResult.Failure("Who should I follow on Instagram?")
                """You are in Instagram. Follow the profile "$query".
STEPS:
1. Tap the Search icon in the bottom nav
2. Type "$query"
3. Tap the most relevant profile result
4. On the profile page, tap the blue "Follow" button
5. Confirm — the button should now say "Following"
6. Report: followed @$query successfully"""
            }

            "like" -> {
                val target = query.ifBlank { "the most recent post in the feed" }
                """You are in Instagram. Like $target.
STEPS:
1. ${if (query.isNotBlank()) "Go to the profile of \"$query\" via Search, then open their most recent post" else "Look at the home feed and find the first post"}
2. Tap the heart ❤️ icon below the post (or double-tap the photo)
3. The heart should turn red — confirm the post is liked
4. Report: liked the post by [username]"""
            }

            "comment" -> {
                if (message.isBlank()) return SkillResult.Failure("What should I comment?")
                val target = query.ifBlank { "the first post in the feed" }
                val commentGoal = """You are in Instagram. Comment "$message" on $target.
STEPS:
1. ${if (query.isNotBlank()) "Search for \"$query\" and open their profile, then tap their most recent post." else "Tap the first post in the home feed to open it."}
2. Tap the comment (speech bubble 💬) icon below the post.
3. The comments section opens — tap the text field at the bottom that says "Add a comment...".
4. Type "$message".
5. Tap "Post" or the send button to submit the comment.
6. Confirm the comment is visible in the comments list.
STRICT RULES:
- Do NOT type in the search bar — only in the comment input at the bottom.
- Do NOT post on the wrong account's post."""
                return SkillResult.NeedsConfirmation(
                    prompt = "💬 *Instagram Comment*\n\nOn: *${query.ifBlank { "first post in feed" }}*\nComment: \"$message\"\n\nReply *YES* to post.",
                    onConfirm = {
                        val result = agent.executeGoal(runner, commentGoal, maxSteps = 22)
                        SkillResult.Success(result)
                    }
                )
            }

            "dm" -> {
                if (query.isBlank()) return SkillResult.Failure("Who should I send the DM to?")
                if (message.isBlank()) return SkillResult.Failure("What should the DM say?")
                val dmGoal = """You are in Instagram. Send a DM to "$query" saying "$message".
STEPS:
1. Tap the paper airplane (Direct Messages) icon at the top right of the home screen.
2. Tap the compose/pencil icon or the search bar in DMs.
3. Type "$query" to find the contact.
4. Tap the matching conversation or contact.
5. Tap the message input field at the bottom.
6. Type "$message".
7. Tap the Send button.
8. Confirm the message was sent.
STRICT RULES:
- Do NOT send to the wrong person — verify the name before tapping Send.
- Do NOT type in the wrong field (search bar vs message input)."""
                return SkillResult.NeedsConfirmation(
                    prompt = "📩 *Instagram DM*\n\nTo: *$query*\nMessage: \"$message\"\n\nReply *YES* to send.",
                    onConfirm = {
                        val result = agent.executeGoal(runner, dmGoal, maxSteps = 22)
                        SkillResult.Success(result)
                    }
                )
            }

            "story", "view_story" -> {
                val target = query.ifBlank { "the first story" }
                """You are in Instagram. View the story of "$target".
STEPS:
1. ${if (query.isNotBlank()) "Search for \"$query\" via the Search tab and go to their profile to find their story ring, or look for their circle in the story bar at the top of home" else "Look at the story bar at the top of the home feed"}
2. Tap the profile picture circle to start viewing the story
3. Watch/read the story content
4. Tap the right side to go to the next story slide
5. Report what the story shows"""
            }

            "reels" ->
                """You are in Instagram. Open Reels.
STEPS:
1. Tap the Reels icon (clapperboard/play ▶ icon) in the bottom navigation bar — it's the third icon
2. The full-screen vertical Reels feed opens
3. Scroll up to see the next reel
4. Report the first reel you see — description, username, audio/song name"""

            "profile" ->
                """You are in Instagram. Open ${if (query.isNotBlank()) "the profile of \"$query\"" else "your own profile"}.
STEPS:
1. ${if (query.isNotBlank()) "Tap Search, type \"$query\", tap the correct profile" else "Tap the person icon (Profile) at the far right of the bottom navigation bar"}
2. Report: username, full name, bio, post count, followers, following"""

            "notifications" ->
                """You are in Instagram. Check notifications.
STEPS:
1. Tap the bell 🔔 icon at the top right of the home screen
2. Read the recent notifications — who liked, commented, followed, or mentioned you
3. Report the last 5-7 notifications"""

            "home" ->
                "You are in Instagram. Go to the home feed. Tap the home (house 🏠) icon at the far left of the bottom navigation bar."

            else ->
                params["goal"] as? String
                    ?: "Do this on Instagram: $action ${query.ifBlank { "" }} ${message.ifBlank { "" }}".trim()
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 22)
        return SkillResult.Success(result)
    }
}
