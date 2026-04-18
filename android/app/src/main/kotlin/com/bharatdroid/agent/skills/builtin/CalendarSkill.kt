package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class CalendarSkill : Skill {

    override val manifest = SkillManifest(
        id = "calendar",
        name = "Google Calendar",
        version = "2.2.0",
        description = "View today's schedule, create events, check meetings on Google Calendar",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK,
        ),
        allowedPackages = setOf("com.google.android.calendar"),
        exampleParamsHint = """{"action": "create", "title": "Team standup", "date": "tomorrow", "time": "10:00 AM"}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase() ?: "today"
        val title = params["title"] as? String ?: params["event"] as? String ?: ""
        val date = params["date"] as? String ?: ""
        val time = params["time"] as? String ?: ""
        val description = params["description"] as? String ?: ""

        if (action in setOf("create", "add", "new")) {
            return createCalendarEventViaIntent(
                runner = runner,
                agent = agent,
                title = title,
                date = date,
                time = time,
                description = description,
            )
        }

        runner.openApp("com.google.android.calendar")
        runner.waitForApp("com.google.android.calendar", timeoutMs = 5000)
        delay(600)
        runner.dismissPopups(2)
        delay(200)

        val goal = when (action) {
            "today", "schedule", "view" ->
                "You are in Google Calendar. Show today's schedule. Read all the events listed for today and tomorrow."

            "week" ->
                "You are in Google Calendar. Switch to week view. Tap 'Week' option and read the events this week."

            "search" ->
                """You are in Google Calendar. Search for event "$title".
                STEPS: 1) Tap the search icon (magnifying glass). 2) Type "$title". 3) If multiple matches appear, prefer the exact title match${if (date.isNotBlank()) " on '$date'" else ""}${if (time.isNotBlank()) " at '$time'" else ""}. 4) Read the matching events - title, date, time."""

            "delete" ->
                """You are in Google Calendar. Delete event "$title".
                STEPS: 1) Tap the search icon and search for "$title". 2) Open the exact matching event${if (date.isNotBlank()) " on '$date'" else ""}${if (time.isNotBlank()) " at '$time'" else ""}. 3) Tap the delete or trash icon. 4) Confirm deletion."""

            else ->
                params["goal"] as? String ?: "Do this in Google Calendar: $action $title $date $time".trim()
        }

        val maxSteps = when (action) {
            "delete" -> 18
            else -> 15
        }

        val result = agent.executeGoal(runner, goal, maxSteps = maxSteps)
        return SkillResult.Success(result)
    }
}
