package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class NotesSkill : Skill {

    override val manifest = SkillManifest(
        id = "notes",
        name = "Google Keep Notes",
        version = "2.0.0",
        description = "Create notes, to-do lists, reminders on Google Keep — any notes task",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK,
        ),
        allowedPackages = setOf("com.google.android.keep"),
        exampleParamsHint = """{"action": "create", "title": "Shopping List", "content": "Milk, Eggs, Bread"}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase() ?: "read"
        val title = params["title"] as? String ?: ""
        val content = params["content"] as? String ?: params["text"] as? String ?: ""
        val query = params["query"] as? String ?: title

        runner.openApp("com.google.android.keep")
        runner.waitForApp("com.google.android.keep", timeoutMs = 5000)
        delay(600)
        runner.dismissPopups(2)
        delay(200)

        val goal = when (action) {
            "create", "add", "new" ->
                """You are in Google Keep notes app. Create a new note.
                STEPS: 1) Tap 'Take a note...' area or the '+' button. ${if (title.isNotBlank()) "2) Tap the Title field and type '$title'. 3) Tap the note body and type '$content'." else "2) Tap the note body and type '$content'."} ${if (action.isNotBlank()) "4) Tap the back button or checkmark to save. Google Keep auto-saves." else ""}"""

            "search", "find" ->
                """You are in Google Keep. Search for note "$query".
                STEPS: 1) Tap the search icon (magnifying glass). 2) Type "$query". 3) Read the matching notes found."""

            "read", "list" ->
                "You are in Google Keep. Read the most recent notes. Scroll through and tell me the note titles and a brief preview of each."

            "pin", "reminder" ->
                """You are in Google Keep. ${if (title.isNotBlank()) "Find note '$title' and" else ""} set a reminder.
                STEPS: 1) ${if (title.isNotBlank()) "Tap the note '$title'." else "Tap the relevant note."} 2) Tap the bell/reminder icon. 3) Set the time or location reminder as needed. 4) Tap Save."""

            else ->
                params["goal"] as? String ?: "Do this in Google Keep: $action $title $content".trim()
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 15)
        return SkillResult.Success(result)
    }
}
