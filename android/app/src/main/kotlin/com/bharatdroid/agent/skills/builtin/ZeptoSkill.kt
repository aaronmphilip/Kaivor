package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class ZeptoSkill : Skill {

    override val manifest = SkillManifest(
        id = "zepto",
        name = "Zepto Grocery Order",
        version = "5.0.0",
        description = "Search and order groceries from Zepto (10-minute delivery) — any grocery task",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK, Permission.PAYMENT,
        ),
        allowedPackages = setOf("com.zepto.app"),
        exampleParamsHint = """{"action": "search", "item": "milk 1 litre"}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase() ?: "search"
        val item = params["item"] as? String ?: params["query"] as? String ?: ""
        val quantity = (params["quantity"] as? Number)?.toLong() ?: 1L

        runner.openApp("com.zepto.app")
        runner.waitForApp("com.zepto.app", timeoutMs = 6000)
        delay(800)
        runner.dismissPopups(3)
        delay(200)

        val goal = when (action) {
            "search" -> {
                if (item.isBlank()) return SkillResult.Failure("What grocery item do you need?")
                """You are in the Zepto app. Search for "$item".
STEPS: 1) Tap the Search bar or the magnifying glass icon at the top. 2) Type "$item". 3) Wait for search results to load. 4) Read out the top results — product names, weights/sizes, prices, and availability. Do not add to cart yet."""
            }
            "order", "add" -> {
                if (item.isBlank()) return SkillResult.Failure("What do you want to order from Zepto?")
                """You are in the Zepto app. Find and add "$item" (quantity: $quantity) to the cart.
STEPS: 1) Tap the Search bar at the top. 2) Type "$item". 3) Tap the most relevant result from the search suggestions. 4) On the product listing page, find the best matching product. 5) Tap the '+' or 'Add' button next to it to add to cart. 6) If quantity is more than 1, tap '+' again to increase quantity to $quantity. 7) Confirm the item is in the cart by checking the cart icon count. Report back what was added and the price."""
            }
            else ->
                params["goal"] as? String ?: "Do this in Zepto: $action $item".trim()
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 20)
        return SkillResult.Success(result)
    }
}
