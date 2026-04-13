package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class ChromeSkill : Skill {

    override val manifest = SkillManifest(
        id = "chrome",
        name = "Chrome Web Browser",
        version = "5.0.0",
        description = "Search the web, open URLs, fill forms, read pages, do any multi-step web task",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK,
        ),
        allowedPackages = setOf("com.android.chrome"),
        exampleParamsHint = """{"action": "search", "query": "weather in Mumbai today"}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase() ?: "search"
        val query = params["query"] as? String ?: ""
        val url = params["url"] as? String ?: ""

        runner.openApp("com.android.chrome")
        runner.waitForApp("com.android.chrome", timeoutMs = 6000)
        delay(600)
        runner.dismissPopups(2)
        delay(200)

        val goal = when (action) {
            "search" ->
                """You are in Chrome browser. Search for "$query".
                STEPS: 1) Tap the address bar at the very top of Chrome. 2) Clear it and type "$query". 3) Press enter. 4) Wait for the page to fully load. 5) Read the results."""

            "open" ->
                """You are in Chrome browser. Open the URL "$url".
                STEPS: 1) Tap the address bar at the top. 2) Clear it and type "$url". 3) Press enter. 4) Wait for the page to load."""

            "read" -> {
                val screen = runner.readScreen()
                return SkillResult.Success("Current page:\n```\n${screen.take(800)}\n```")
            }

            else -> // goal or any complex task
                params["goal"] as? String
                    ?: """You are in Chrome browser. Do this: $action $query $url""".trim()
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 20)
        return SkillResult.Success(result)
    }
}
