package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class FlipkartSkill : Skill {

    override val manifest = SkillManifest(
        id = "flipkart",
        name = "Flipkart Shopping",
        version = "5.0.0",
        description = "Search products, check prices, track orders on Flipkart",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK, Permission.PAYMENT,
        ),
        allowedPackages = setOf("com.flipkart.android"),
        exampleParamsHint = """{"action": "search", "query": "boAt Airdopes 141", "maxPrice": 1500}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val query = params["query"] as? String ?: params["goal"] as? String ?: ""
        val maxPrice = (params["maxPrice"] as? Long)?.toInt()
        val action = (params["action"] as? String)?.lowercase() ?: "search"

        runner.openApp("com.flipkart.android")
        runner.waitForApp("com.flipkart.android", timeoutMs = 6000)
        delay(600)
        runner.dismissPopups(2)
        delay(200)

        val goal = if (action == "goal") {
            params["goal"] as? String ?: query
        } else {
            buildString {
                append("TASK: Search for and show \"$query\" on Flipkart.\n\n")
                append("CRITICAL RULES:\n")
                append("1. Find the search INPUT FIELD at top (must be editable text, NOT icon)\n")
                append("2. Tap only the TEXT input field. NEVER use voice/mic buttons\n")
                append("3. If you see voice UI, press back immediately\n")
                append("4. Type \"$query\" into the search field\n")
                append("5. Press Enter to search\n")
                append("6. Wait for results\n")
                append("7. Scroll down to see options\n")
                append("8. Find the best product")
                if (maxPrice != null) append(" under Rs $maxPrice")
                append("\n")
                append("9. Tap to show product details\n\n")
                append("DO NOT use voice. Text search only.")
            }
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 20)
        return SkillResult.Success(result)
    }
}
