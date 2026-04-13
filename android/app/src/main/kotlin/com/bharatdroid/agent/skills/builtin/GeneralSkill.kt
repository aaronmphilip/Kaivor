package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*

/**
 * GENERAL AGENT — The catch-all skill.
 *
 * When no specific skill matches the user's request, this skill
 * uses the AI-driven ScreenAgent to accomplish the task by:
 * 1. Reading the current screen
 * 2. Asking AI what to do next
 * 3. Executing the action
 * 4. Repeating until the goal is achieved
 *
 * This makes BharatDroid capable of handling ANY task on ANY app,
 * not just the hardcoded skill flows.
 */
class GeneralSkill : Skill {

    override val manifest = SkillManifest(
        id = "general",
        name = "General Agent",
        version = "1.0.0",
        description = "Handle ANY task on the phone. Opens apps, reads screens, taps buttons, types text, scrolls, navigates. Use this when no other specific skill matches. Can work with any app.",
        author = "bharatdroid-team",
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
                // App not found — continue anyway, work with current screen
            }
        }

        val result = agent.executeGoal(context.runner, goal, maxSteps = 30)
        return SkillResult.Success(result)
    }
}
