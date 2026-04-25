package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class FileManagerSkill : Skill {

    override val manifest = SkillManifest(
        id = "files",
        name = "File Manager",
        version = "2.0.0",
        description = "Browse files, search documents, manage downloads on Android",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK,
        ),
        allowedPackages = setOf(
            "com.google.android.apps.nbu.files",
            "com.google.android.documentsui",
            "com.mi.android.globalFileexplorer",
            "com.coloros.filemanager",
        ),
        exampleParamsHint = """{"action": "browse", "folder": "Downloads"}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase() ?: "browse"
        val folder = params["folder"] as? String ?: ""
        val query = params["search"] as? String ?: params["file"] as? String ?: params["query"] as? String ?: ""

        // Try file manager apps in order of preference
        val fileApps = listOf(
            "com.google.android.apps.nbu.files",
            "com.google.android.documentsui",
            "com.mi.android.globalFileexplorer",
            "com.coloros.filemanager",
        )
        var opened = false
        for (app in fileApps) {
            try {
                runner.openApp(app)
                if (runner.waitForApp(app, timeoutMs = 3000)) { opened = true; break }
            } catch (_: Exception) { continue }
        }
        if (!opened) return SkillResult.Failure("No file manager found. Install Google Files.")
        delay(600)
        runner.dismissPopups(1)
        delay(200)

        val goal = when (action) {
            "browse", "view" ->
                """You are in a File Manager app. Browse to "${folder.ifBlank { "Downloads" }}" folder.
                STEPS: 1) Look for '${folder.ifBlank { "Downloads" }}' in the sidebar or main list. 2) Tap it to open. 3) Read the files present in that folder."""

            "search", "find" ->
                """You are in a File Manager. Search for "$query".
                STEPS: 1) Tap the search icon (magnifying glass) at the top. 2) Type "$query". 3) Wait for results. 4) Read the file names and locations found."""

            "downloads" ->
                """You are in a File Manager. Open the Downloads folder.
                STEPS: 1) Tap 'Downloads' from the sidebar or main category list. 2) List the recent files with their names and sizes."""

            "open" ->
                """You are in a File Manager. Open the file "$query".
                STEPS: 1) If not visible, search for "$query" using the search icon. 2) Tap the file to open it. 3) If prompted to choose an app, tap the most relevant one."""

            "delete" -> {
                if (query.isBlank()) return SkillResult.Failure("Which file should I delete?")
                val deleteGoal = """You are in a File Manager. Delete the file "$query".
STEPS:
1. If not visible, tap the search icon and search for "$query".
2. Long-press the file to select it (a checkmark should appear).
3. Tap the delete or trash icon in the toolbar.
4. Confirm deletion when prompted.
5. Report: file deleted successfully or not found."""
                return SkillResult.NeedsConfirmation(
                    prompt = "🗑️ *Delete File*\n\nFile: *$query*\n\n⚠️ This will permanently delete the file. It cannot be undone.\n\nReply *YES* to confirm.",
                    onConfirm = {
                        val result = agent.executeGoal(runner, deleteGoal, maxSteps = 12)
                        SkillResult.Success(result)
                    }
                )
            }

            else ->
                params["goal"] as? String ?: "Do this in Files: $action $folder $query".trim()
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 15)
        return SkillResult.Success(result)
    }
}
