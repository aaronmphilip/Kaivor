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
        uiKnowledge = """
Google Calendar UI guide:
- Month view: full calendar grid; coloured dots appear under dates that have events; tap a date to see its events in a list below the grid
- Day view: hour-by-hour timeline for the selected day; events shown as coloured blocks at their scheduled time slot
- Week view: 7-column grid with hours on the left; coloured event blocks at their time slots
- FAB (create event): blue/coloured + button at the bottom right corner; tap to start creating a new event
- Event creation screen: Title field at the top (large text); date picker and time picker below; All-day toggle; Location field; Description/notes field; Guests (invite attendees); Notification reminders; Save button at the top right
- Event detail screen: shows event title, date and time, location (if set), organiser, attendees list; Edit (pencil) icon top right; Delete (trash) icon top right; RSVP options if invited by someone else
- Search: magnifying glass icon at the top right of any calendar view; type to find events by title or location
- Navigation drawer: three-line hamburger top left → shows calendar list (My Calendar, Other people's calendars, Google contacts), Settings
""".trimIndent(),
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

            "delete" -> {
                if (title.isBlank()) return SkillResult.Failure("Which event should I delete?")
                val deleteGoal = buildString {
                    appendLine("You are in Google Calendar. Delete event \"$title\".")
                    appendLine("STEPS:")
                    appendLine("1. Tap the search icon (magnifying glass) at the top.")
                    appendLine("2. Type \"$title\" and find the exact match${if (date.isNotBlank()) " on '$date'" else ""}${if (time.isNotBlank()) " at '$time'" else ""}.")
                    appendLine("3. Tap the event to open its detail screen.")
                    appendLine("4. Tap the delete (trash) icon at the top right.")
                    appendLine("5. Confirm deletion if prompted.")
                    appendLine("6. Report: event deleted or not found.")
                }
                return SkillResult.NeedsConfirmation(
                    prompt = "🗑️ *Delete Calendar Event*\n\nEvent: *$title*${if (date.isNotBlank()) "\nDate: $date" else ""}${if (time.isNotBlank()) "\nTime: $time" else ""}\n\n⚠️ This will permanently delete the event.\n\nReply *YES* to confirm.",
                    onConfirm = {
                        val result = agent.executeGoal(runner, deleteGoal, maxSteps = 50)
                        SkillResult.Success(result)
                    }
                )
            }

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
