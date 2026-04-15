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

        // Direct search: type into search field before AI takes over
        if (action == "search" && query.isNotBlank()) {
            val directTyped = runner.typeInFieldWithHint("Search for Products", query)
                || runner.typeInFieldWithHint("Search", query)
            if (directTyped) {
                delay(200)
                runner.pressEnter()
                delay(1500)
            } else {
                val (w, _) = runner.getScreenSize()
                runner.tapAtPoint(w * 0.35f, 140f)
                delay(400)
                runner.typeReliably(query)
                delay(200)
                runner.pressEnter()
                delay(1500)
            }
        }

        val goal = if (action == "goal") {
            params["goal"] as? String ?: query
        } else {
            buildString {
                append("TASK: Find the BEST \"$query\" on Flipkart.\n\n")
                if (action == "search") append("Search already submitted. You should see results.\n\n")
                append("STEPS:\n")
                append("1. Scroll through results to compare options\n")
                if (maxPrice != null) append("2. Filter by price under Rs $maxPrice\n")
                append("3. Look for high ratings (4+ stars) and good review count\n")
                append("4. Tap the best match to see details\n")
                append("5. On product page: scroll to read reviews, specs, features\n")
                append("6. Report findings — name, price, rating, key features\n\n")
                append("DO NOT press back repeatedly. DO NOT go to home screen.\n")
                append("DO NOT tap mic/camera/voice icons. Stay on results page.")
            }
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 20)
        return SkillResult.Success(result)
    }
}
