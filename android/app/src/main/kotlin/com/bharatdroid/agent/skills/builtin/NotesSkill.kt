package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class NotesSkill : Skill {

    override val manifest = SkillManifest(
        id = "notes",
        name = "Google Keep Notes",
        version = "2.0.0",
        description = "Create notes, to-do lists, reminders on Google Keep — any notes task",
        author = "bharatclaw-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK,
        ),
        allowedPackages = setOf("com.google.android.keep"),
        exampleParamsHint = """{"action": "create", "title": "Shopping List", "content": "Milk, Eggs, Bread"}""",
        uiKnowledge = """
Google Keep UI guide:
- Home screen: grid or list of coloured note cards; search bar at the top ("Search your notes"); new-note input at the bottom ("Take a note..."); bottom nav: Notes | Reminders | Labels | Archive.
- Creating a note: tap "Take a note..." at the bottom ? expands into a full-screen editor with separate Title (top) and Note (body) fields.
- Title field: smaller text box at the top of the editor labelled "Title" — type the note title here.
- Note body field: larger text area below the title — tap it and type all content; supports multi-line.
- Checklist / To-do: tap the checkbox icon in the bottom toolbar to convert the note into a checklist; each line becomes a checkable item.
- Saving: Google Keep auto-saves as you type. Tap the back arrow or the ? to close and save.
- Note colours: rounded colour palette at the bottom of the editor; changing colour helps organise.
- Labels: tap the three-dot menu ? Labels ? assign label to categorise notes.
- Reminders (bell icon): tap the bell ?? at the top of the editor ? set date/time or location trigger.
- Pinning: tap the pin ?? icon at the top to pin a note so it stays at the top of the list.
- Search: tap the search bar at the top of the home screen ? type keywords; results show matching notes.
- Archive: swipe a note left to archive it (removes from main view but keeps it in Archive).
- Delete: tap the three-dot menu on a note ? Delete (moves to Trash for 7 days).
""".trimIndent(),
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

        // Escape quotes in user content so they don't break the goal string
        val safeTitle = title.replace("'", "\\'").replace("\"", "\\\"")
        val safeContent = content.replace("'", "\\'").replace("\"", "\\\"")
        val safeQuery = query.replace("'", "\\'").replace("\"", "\\\"")

        val goal = when (action) {
            "create", "add", "new" -> buildString {
                appendLine("You are in Google Keep notes app. Create a new note.")
                appendLine("STEPS:")
                appendLine("1) Tap the 'Take a note...' area or the '+' button to create a new note.")
                if (safeTitle.isNotBlank()) {
                    appendLine("2) Tap the Title field and type the title: $safeTitle")
                    appendLine("3) Tap the note body field (below title) and type the full content in ONE type action:")
                    appendLine("   $safeContent")
                    appendLine("   Use \\n for line breaks. Type ALL the content in a single type action — do NOT type line by line.")
                } else {
                    appendLine("2) Tap the note body and type the full content in ONE type action:")
                    appendLine("   $safeContent")
                }
                appendLine("4) Google Keep auto-saves. Tap back or the checkmark to confirm save.")
                append("IMPORTANT: Type the ENTIRE content in one go. Do NOT type one line then delete and retype.")
            }

            "search", "find" ->
                "You are in Google Keep. Search for note matching: $safeQuery\nSTEPS: 1) Tap the search icon. 2) Type: $safeQuery 3) Read the matching notes found."

            "read", "list" ->
                "You are in Google Keep. Read the most recent notes. Scroll through and report the note titles and a brief preview of each."

            "pin", "reminder" -> buildString {
                append("You are in Google Keep. ")
                if (safeTitle.isNotBlank()) append("Find note titled $safeTitle and ")
                appendLine("set a reminder.")
                appendLine("STEPS: 1) Tap the relevant note. 2) Tap the bell/reminder icon. 3) Set the time. 4) Tap Save.")
            }

            else ->
                params["goal"] as? String ?: "Do this in Google Keep: $action $safeTitle $safeContent".trim()
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 40)
        return SkillResult.Success(result)
    }
}
