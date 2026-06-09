package com.bharatdroid.agent.skills.builtin

import android.content.Intent
import com.bharatdroid.agent.skills.*

/**
 * GENERAL AGENT - The catch-all skill.
 *
 * When no specific skill matches the user's request, this skill
 * uses the AI-driven ScreenAgent to accomplish the task by:
 * 1. Reading the current screen
 * 2. Asking AI what to do next
 * 3. Executing the action
 * 4. Repeating until the goal is achieved
 *
 * This makes BharatClaw capable of handling ANY task on ANY app,
 * not just the hardcoded skill flows.
 */
class GeneralSkill : Skill {

    override val manifest = SkillManifest(
        id = "general",
        name = "General Agent",
        version = "1.0.0",
        description = "Handle ANY task on the phone. Opens apps, reads screens, taps buttons, types text, scrolls, navigates. Use this when no other specific skill matches. Can work with any app.",
        author = "BharatClaw-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK, Permission.SENSITIVE_READ,
        ),
        allowedPackages = emptySet(), // Can work with any app
        exampleParamsHint = """{"goal": "open calculator and compute 25 times 4", "app": "com.google.android.calculator"}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val goal = params["goal"] as? String
            ?: params["query"] as? String
            ?: params["task"] as? String
            ?: return SkillResult.Failure("What should I do? Provide a goal.")

        val agent = context.agent
            ?: return SkillResult.Failure("Smart agent not available. Check AI configuration.")

        // If an app package is specified, open it first
        val appPackage = params["app"] as? String
        if (appPackage != null && appPackage.isNotBlank()) {
            try {
                context.runner.openApp(appPackage)
                context.runner.waitForApp(appPackage, timeoutMs = 5000)
                kotlinx.coroutines.delay(1500)
                context.runner.dismissPopups(2)
            } catch (_: Exception) {
                // App not found - try to resolve by name via package manager
                tryOpenAppByName(context, appPackage)
            }
        } else {
            // No explicit package - try to extract app name from goal and open it.
            // e.g. goal = "open Obsidian" ? search for "Obsidian" in installed apps
            val appNameMatch = APP_OPEN_REGEX.find(goal.lowercase())
            if (appNameMatch != null) {
                val appName = appNameMatch.groupValues[1].trim()
                tryOpenAppByName(context, appName)
            }
        }

        val result = agent.executeGoal(context.runner, goal, maxSteps = 70)
        return SkillResult.Success(result)
    }

    /**
     * Try to open an app by its display name (not package name).
     * Uses Android's PackageManager to search installed apps.
     * e.g. "Obsidian" ? "md.obsidian", "Spotify" ? "com.spotify.music"
     */
    private suspend fun tryOpenAppByName(context: SkillContext, name: String) {
        try {
            val pm = context.runner.getContext().packageManager
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(intent, 0)

            // Find the best match by app label
            val match = apps.firstOrNull { resolveInfo ->
                val label = resolveInfo.loadLabel(pm).toString()
                label.equals(name, ignoreCase = true)
            } ?: apps.firstOrNull { resolveInfo ->
                val label = resolveInfo.loadLabel(pm).toString()
                label.contains(name, ignoreCase = true)
            }

            if (match != null) {
                val pkg = match.activityInfo.packageName
                context.runner.openApp(pkg)
                context.runner.waitForApp(pkg, timeoutMs = 5000)
                kotlinx.coroutines.delay(1000)
                context.runner.dismissPopups(2)
            }
        } catch (_: Exception) {
            // Couldn't resolve - executeGoal will work with whatever is on screen
        }
    }

    companion object {
        // Matches "open X", "launch X", "start X" at the beginning of a goal
        private val APP_OPEN_REGEX = Regex("""^(?:open|launch|start|go to)\s+(.+?)(?:\s+app)?(?:\s+and\s+.*)?$""")
    }
}
