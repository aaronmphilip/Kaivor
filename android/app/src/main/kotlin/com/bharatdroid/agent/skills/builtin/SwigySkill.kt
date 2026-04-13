package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class SwigySkill : Skill {

    override val manifest = SkillManifest(
        id = "swiggy",
        name = "Swiggy Food Order",
        version = "7.0.0",
        description = "Search and order food from Swiggy — any order, filter, or delivery task",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK, Permission.PAYMENT,
        ),
        allowedPackages = setOf("in.swiggy.android"),
        exampleParamsHint = """{"query":"biryani","maxPrice":200,"filter":"fastest","action":"order"}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val query = params["query"] as? String ?: params["goal"] as? String
            ?: return SkillResult.Failure("What food do you want to order?")
        val maxPrice = (params["maxPrice"] as? Long)?.toInt()
        val filter = (params["filter"] as? String)?.lowercase() ?: ""
        val action = (params["action"] as? String)?.lowercase() ?: "search"

        runner.openApp("in.swiggy.android")
        runner.waitForApp("in.swiggy.android", timeoutMs = 6000)
        delay(600)
        runner.dismissPopups(2)
        delay(200)

        val priceNote = if (maxPrice != null) " under Rs $maxPrice" else ""
        val filterNote = when {
            filter.contains("fast") -> ", fastest delivery"
            filter.contains("rating") || filter.contains("top") -> ", highest rated"
            filter.contains("price") || filter.contains("cheap") -> ", cheapest first"
            else -> ""
        }

        val goal = if (action == "goal") {
            params["goal"] as? String ?: query
        } else {
            """
            You are in Swiggy food delivery app.
            TASK: Find "$query"$priceNote$filterNote and ${if (action == "order" || action == "add_to_cart") "order it" else "show results"}.

            STEPS:
            1. Find and tap the search bar or search icon at the top of the screen.
            2. Type "$query" in the search field.
            3. Press enter or tap search.
            4. Wait for restaurant/food results to appear.
            ${if (filter.isNotBlank()) "5. Apply filter: $filterNote." else ""}
            ${if (maxPrice != null) "5. Look for options$priceNote." else ""}
            ${if (action == "order" || action == "add_to_cart")
                "6. Tap the first restaurant. 7. Find the item and tap ADD. 8. Tap Go to Cart. 9. Tap Proceed to Pay."
            else
                "6. Tap the first restaurant to see the menu."}
            """.trimIndent()
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 25)
        return SkillResult.Success(result)
    }
}
