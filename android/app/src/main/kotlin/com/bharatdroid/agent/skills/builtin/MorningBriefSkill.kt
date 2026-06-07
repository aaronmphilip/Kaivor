package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

/**
 * MorningBriefSkill — the "hey, good morning" composite.
 * Reads today's calendar events, current weather (via Google search in Chrome),
 * and the top unread email subject line. Returns a combined summary.
 *
 * Hackathon one-liner: "Give me my morning brief."
 *
 * Example params:
 *   {}  (no params needed — uses today's date and current location)
 */
class MorningBriefSkill : Skill {

    override val manifest = SkillManifest(
        id = "morning_brief",
        name = "Morning Brief (Calendar + Weather + Mail)",
        version = "1.0.0",
        description = "Reads today's schedule, current weather, and top unread email for a morning briefing.",
        author = "bharatclaw-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK,
        ),
        allowedPackages = setOf(
            "com.google.android.calendar",
            "com.android.chrome",
            "com.google.android.gm",
        ),
        exampleParamsHint = """{"city":"Bangalore"}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val city = params["city"] as? String ?: "my location"
        val skipMail = (params["skipMail"] as? Boolean) ?: false

        // --- 1. Calendar ---------------------------------------------------------
        runner.openApp("com.google.android.calendar")
        runner.waitForApp("com.google.android.calendar", timeoutMs = 6000)
        delay(700)
        runner.dismissPopups(2)
        delay(200)

        val calGoal = buildString {
            append("You are in Google Calendar. READ today's schedule.\n\n")
            append("STEPS:\n")
            append("1. Make sure you're on today's view (tap 'Today' button if needed).\n")
            append("2. List all events visible for TODAY — title + time for each.\n")
            append("3. Call done with summary like: '3 events today: 10am Standup, 2pm Client call, 6pm Gym'.\n")
            append("   If no events today, say: 'No events scheduled today'.\n\n")
            append("DO NOT create or modify events. Just READ.")
        }
        val calResult = agent.executeGoal(runner, calGoal, maxSteps = 70)

        // --- 2. Weather via Chrome ----------------------------------------------
        runner.openApp("com.android.chrome")
        runner.waitForApp("com.android.chrome", timeoutMs = 6000)
        delay(700)
        runner.dismissPopups(2)
        delay(200)

        val weatherGoal = buildString {
            append("You are in Chrome. Get the current weather for $city.\n\n")
            append("STEPS:\n")
            append("1. Tap the address/search bar at the top.\n")
            append("2. Type: weather $city\n")
            append("3. Press Enter and WAIT for the Google result page to load.\n")
            append("4. Google shows a weather card at the top: temperature, condition, high/low.\n")
            append("5. READ the current temperature (e.g. '28°C') and condition (e.g. 'Partly cloudy').\n")
            append("6. Call done with summary EXACTLY like: 'Weather: 28°C, Partly cloudy, high 32°/ low 22°'.\n\n")
            append("DO NOT tap random results. DO NOT open other tabs.")
        }
        val weatherResult = agent.executeGoal(runner, weatherGoal, maxSteps = 70)

        // --- 3. Gmail (optional) ------------------------------------------------
        val mailResult = if (skipMail) "(skipped)" else {
            runner.openApp("com.google.android.gm")
            runner.waitForApp("com.google.android.gm", timeoutMs = 6000)
            delay(800)
            runner.dismissPopups(2)
            delay(200)
            val mailGoal = buildString {
                append("You are in Gmail on the Inbox. READ the TOP unread email.\n\n")
                append("STEPS:\n")
                append("1. Look at the Primary inbox list.\n")
                append("2. Find the first UNREAD email (shown in BOLD — unread emails are bolder than read ones).\n")
                append("3. Read its sender name and subject line.\n")
                append("4. Call done with summary EXACTLY like: 'Top unread: <Sender> — <Subject>'.\n")
                append("   If inbox is all read or empty, say 'Inbox clear — no unread emails'.\n\n")
                append("DO NOT open any email. DO NOT reply. Just READ the list.")
            }
            agent.executeGoal(runner, mailGoal, maxSteps = 65)
        }

        // --- Summary ------------------------------------------------------------
        val summary = buildString {
            appendLine("?? *Good morning! Here's your brief:*")
            appendLine()
            appendLine("??? *Today's schedule*")
            appendLine(calResult)
            appendLine()
            appendLine("??? *Weather*")
            appendLine(weatherResult)
            if (!skipMail) {
                appendLine()
                appendLine("?? *Inbox*")
                appendLine(mailResult)
            }
            appendLine()
            appendLine("Have a great day! ?")
        }
        return SkillResult.Success(summary)
    }
}
