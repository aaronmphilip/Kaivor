package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class GmailSkill : Skill {

    override val manifest = SkillManifest(
        id = "gmail",
        name = "Gmail",
        version = "4.0.0",
        description = "Read, compose, send, reply, search, delete emails — any Gmail task",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK, Permission.SENSITIVE_READ,
        ),
        allowedPackages = setOf("com.google.android.gm"),
        exampleParamsHint = """{"action": "compose", "to": "boss@company.com", "subject": "Update", "body": "Hi team..."}""",
        uiKnowledge = """
Gmail UI guide:
- Inbox: vertical list of emails; each row shows sender name (left), subject line (bold if unread), preview snippet, timestamp (right)
- Compose: red pencil FAB at bottom right → full compose screen with To / CC / Subject / Body fields; Send button (paper plane ▶) at top right
- Search: tap the search bar at the top or the magnifying glass icon
- Sidebar / navigation drawer: tap three-line hamburger (top left) → Inbox, Starred, Sent, Drafts, Spam, All Mail
- Email thread: tap an email to open; Reply, Reply All, Forward buttons appear at the bottom; three-dot overflow for Archive/Delete/Label
- Star: tap the star icon on an email row to star/unstar
- Swipe: swipe left = archive, swipe right = delete (default settings)
- Account switcher: tap your avatar/profile picture at the top right to switch Google accounts
""".trimIndent(),
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase() ?: "read"
        val search = params["search"] as? String ?: params["query"] as? String ?: ""
        val to = params["to"] as? String ?: ""
        val subject = params["subject"] as? String ?: ""
        val body = params["body"] as? String ?: params["message"] as? String ?: ""

        runner.openApp("com.google.android.gm")
        runner.waitForApp("com.google.android.gm", timeoutMs = 7000)
        delay(800)
        runner.dismissPopups(2)
        delay(200)

        // For search actions, type directly into the search bar first
        if (action in setOf("search", "find") && search.isNotBlank()) {
            val typed = runner.typeInFieldWithHint("Search mail", search)
                || runner.typeInFieldWithHint("Search", search)
            if (typed) {
                delay(200)
                runner.pressEnter()
                delay(1500)
            } else {
                val (w, h) = runner.getScreenSize()
                runner.tapAtPoint(w * 0.5f, h * 0.07f)
                delay(400)
                runner.typeReliably(search)
                delay(200)
                runner.pressEnter()
                delay(1500)
            }
        }

        val goal = when (action) {
            "read" ->
                if (search.isNotBlank())
                    """You are in Gmail. Search results for "$search" are showing.
STEPS:
1. Tap the first email in the results
2. Read the full email content
3. Summarize: who sent it, when, and what it says"""
                else
                    """You are in Gmail. Show recent unread emails.
STEPS:
1. Look at the inbox list
2. Find unread emails (bold text)
3. Report the top 5 emails — sender name, subject, and a brief preview of each"""

            "compose", "send" -> {
                if (to.isBlank()) return SkillResult.Failure("Who should I send the email to? Provide a 'to' address.")
                if (subject.isBlank()) return SkillResult.Failure("What is the email subject?")
                if (body.isBlank()) return SkillResult.Failure("What should the email say?")
                val composeGoal = """You are in Gmail. Compose and send an email.
STEPS:
1. Tap the Compose button (pencil/+ FAB at bottom right).
2. Wait for the compose screen to open.
3. Tap the "To" field and type "$to".
   → A suggestion dropdown appears below the field — TAP the best matching contact suggestion.
   → If no suggestion appears (e.g. it's a full email address), press Enter or tap space to add the recipient chip.
   → The recipient chip must appear before moving on.
4. Tap the "Subject" field and type "$subject".
5. Tap the email body area (below Subject) and type "$body".
6. Tap the Send button (paper plane ▶ at top right).
7. Confirm the email was sent — you should return to inbox or see a "Message sent" toast.
STRICT RULES:
- ALWAYS tap a suggestion from the dropdown after typing the recipient — this is how Gmail confirms the address.
- Do NOT tap outside the compose window — it will discard the draft.
- The To field must show the recipient chip before sending."""
                return SkillResult.NeedsConfirmation(
                    prompt = "📧 *Send Email via Gmail*\n\nTo: *$to*\nSubject: *$subject*\nMessage: \"${body.take(120)}${if (body.length > 120) "…" else ""}\"\n\nReply *YES* to send.",
                    onConfirm = {
                        val result = agent.executeGoal(runner, composeGoal, maxSteps = 60)
                        SkillResult.Success(result)
                    }
                )
            }

            "reply" -> {
                if (body.isBlank()) return SkillResult.Failure("What should I reply?")
                val emailContext = search.ifBlank { subject }
                val replyGoal = """You are in Gmail. Reply to ${if (emailContext.isNotBlank()) "email about \"$emailContext\"" else "the first email in the inbox"}.
STEPS:
1. ${if (emailContext.isNotBlank()) "Search for or find the email about \"$emailContext\" and tap to open it." else "Tap the first email in the inbox to open it."}
2. Scroll to the bottom of the email thread.
3. Tap the "Reply" button.
4. Tap the reply body field and type "$body".
5. Tap the Send button (▶).
6. Confirm the reply was sent.
STRICT RULES:
- Do NOT reply to the wrong email thread.
- Do NOT tap Reply All unless the user specifically asked."""
                return SkillResult.NeedsConfirmation(
                    prompt = "↩️ *Reply Email via Gmail*\n\n${if (emailContext.isNotBlank()) "Re: *$emailContext*\n" else ""}Reply: \"${body.take(120)}${if (body.length > 120) "…" else ""}\"\n\nReply *YES* to send.",
                    onConfirm = {
                        val result = agent.executeGoal(runner, replyGoal, maxSteps = 55)
                        SkillResult.Success(result)
                    }
                )
            }

            "search", "find" ->
                """You are in Gmail. Search results for "$search" are now showing.
STEPS:
1. Look at the matching emails listed
2. Read the subject lines, senders, and dates
3. Tap the most relevant email and read its content
4. Report: subject, sender, date, key points"""

            "unread" ->
                """You are in Gmail. Show all unread emails.
STEPS:
1. In the inbox, look for bold emails (unread)
2. If needed, tap the search bar and type "is:unread" to filter
3. Report: how many unread emails, who they are from, and the subjects"""

            "delete", "trash" -> {
                val target = search.ifBlank { subject }
                if (target.isBlank()) return SkillResult.Failure("Which email should I delete?")
                """You are in Gmail. Delete the email "$target".
STEPS:
1. Search for "$target" if not already visible
2. Long-press the email row to select it (a checkmark should appear)
3. Tap the trash/delete icon that appears at the top
4. Confirm it was moved to trash"""
            }

            "star" -> {
                val target = search.ifBlank { subject }
                """You are in Gmail. Star the email "$target".
STEPS:
1. Find the email "$target" in inbox or search for it
2. Tap the star icon on the email row (or open the email and tap the star icon at the top)
3. Confirm it is starred (star turns yellow/gold)"""
            }

            "sent" ->
                """You are in Gmail. Show sent emails.
STEPS:
1. Tap the three-line hamburger menu at the top left
2. Tap "Sent" in the navigation drawer
3. Read the last 5 sent emails — recipient, subject, date"""

            "drafts" ->
                """You are in Gmail. Open Drafts folder.
STEPS:
1. Tap the three-line hamburger menu at the top left
2. Tap "Drafts" in the navigation drawer
3. List the drafts — subject, and who they were being sent to"""

            else ->
                params["goal"] as? String ?: "Do this in Gmail: $action $to $search".trim()
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 60)
        return SkillResult.Success(result)
    }
}
