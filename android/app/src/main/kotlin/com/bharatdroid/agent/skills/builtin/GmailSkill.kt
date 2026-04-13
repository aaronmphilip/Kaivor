package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class GmailSkill : Skill {

    override val manifest = SkillManifest(
        id = "gmail",
        name = "Gmail",
        version = "3.0.0",
        description = "Read emails, compose and send emails, search inbox, reply — any Gmail task",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK, Permission.SENSITIVE_READ,
        ),
        allowedPackages = setOf("com.google.android.gm"),
        exampleParamsHint = """{"action": "compose", "to": "boss@company.com", "subject": "Update", "body": "Hi team..."}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase() ?: "read"
        val search = params["search"] as? String ?: ""
        val to = params["to"] as? String ?: ""
        val subject = params["subject"] as? String ?: ""
        val body = params["body"] as? String ?: params["message"] as? String ?: ""

        runner.openApp("com.google.android.gm")
        runner.waitForApp("com.google.android.gm", timeoutMs = 6000)
        delay(800)
        runner.dismissPopups(2)
        delay(200)

        val goal = when (action) {
            "read" ->
                if (search.isNotBlank())
                    """You are in Gmail. Search for emails about "$search" and read the first result.
                    STEPS: 1) Tap the search bar or search icon at the top. 2) Type "$search". 3) Press Enter. 4) Tap the first email in results. 5) Read and summarize the email content."""
                else
                    "You are in Gmail. Read the latest unread emails. List the subject line, sender, and brief preview of the top 3-5 emails in the inbox."

            "compose", "send" ->
                """You are in Gmail. Compose and send an email.
                STEPS: 1) Tap the Compose button (pencil/+ icon at bottom right). 2) In 'To' field type "$to" then press Enter or Tab. 3) Tap Subject field, type "$subject". 4) Tap the body area, type "$body". 5) Tap the Send button (paper plane icon at top right)."""

            "search" ->
                """You are in Gmail. Search for "$search".
                STEPS: 1) Tap the search bar at top. 2) Type "$search". 3) Press Enter. 4) Read the matching email subjects and senders."""

            "reply" ->
                """You are in Gmail. Reply to the first email in the inbox.
                STEPS: 1) Tap the first email to open it. 2) Tap 'Reply' button at the bottom. 3) Tap the message field and type "$body". 4) Tap the Send button."""

            "unread" ->
                "You are in Gmail. Show unread emails. Read the unread email subjects and senders from the inbox."

            "delete" ->
                """You are in Gmail. Delete the first email matching "$search".
                STEPS: 1) Search for "$search". 2) Long press the email to select it. 3) Tap the delete/trash icon."""

            else ->
                params["goal"] as? String ?: "Do this in Gmail: $action $to $search".trim()
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 20)
        return SkillResult.Success(result)
    }
}
