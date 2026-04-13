package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class CalendarSkill : Skill {

    override val manifest = SkillManifest(
        id = "calendar",
        name = "Google Calendar",
        version = "2.0.0",
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
        val date = params["date"] as? String ?: "today"
        val time = params["time"] as? String ?: ""
        val description = params["description"] as? String ?: ""

        runner.openApp("com.google.android.calendar")
        runner.waitForApp("com.google.android.calendar", timeoutMs = 5000)
        delay(600)
        runner.dismissPopups(2)
        delay(200)

        val goal = when (action) {
            "create", "add", "new" ->
                """You are in Google Calendar. Create a new event.
                STEPS: 1) Tap the '+' or 'Create' button (usually bottom right). 2) In the title field type "$title". 3) ${if (date.isNotBlank()) "Set date to '$date'." else "Keep today's date."} ${if (time.isNotBlank()) "4) Set start time to '$time'." else ""} ${if (description.isNotBlank()) "5) Add description '$description'." else ""} 6) Tap 'Save' to create the event."""

            "today", "schedule", "view" ->
                "You are in Google Calendar. Show today's schedule. Read all the events listed for today and tomorrow."

            "week" ->
                "You are in Google Calendar. Switch to week view. Tap 'Week' option and read the events this week."

            "search" ->
                """You are in Google Calendar. Search for event "$title".
                STEPS: 1) Tap the search icon (magnifying glass). 2) Type "$title". 3) Read the matching events — title, date, time."""

            "delete" ->
                """You are in Google Calendar. Delete event "$title".
                STEPS: 1) Search for "$title". 2) Tap the event. 3) Tap the delete/trash icon. 4) Confirm deletion."""

            else ->
                params["goal"] as? String ?: "Do this in Google Calendar: $action $title $date $time".trim()
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 15)
        return SkillResult.Success(result)
    }
}
