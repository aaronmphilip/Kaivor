package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class BlinkitSkill : Skill {

    override val manifest = SkillManifest(
        id = "blinkit",
        name = "Blinkit Grocery",
        version = "5.0.0",
        description = "Search and order groceries on Blinkit (10-minute delivery) — any grocery task",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK, Permission.PAYMENT,
        ),
        allowedPackages = setOf("com.grofers.customerapp"),
        exampleParamsHint = """{"action": "search", "item": "amul butter 500g"}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase() ?: "search"
        val item = params["item"] as? String ?: params["query"] as? String ?: ""
        val quantity = params["quantity"] as? Long ?: 1L

        runner.openApp("com.grofers.customerapp")
        runner.waitForApp("com.grofers.customerapp", timeoutMs = 6000)
        delay(800)
        runner.dismissPopups(3)
        delay(200)

        val goal = when (action) {
            "search" -> {
                if (item.isBlank()) return SkillResult.Failure("What do you need from Blinkit?")
                """You are in the Blinkit app. Search for "$item".
STEPS: 1) Tap the Search bar at the top of the screen. 2) Type "$item". 3) Wait for search results to load. 4) Read out the top results — product names, weights/sizes, prices, delivery time, and availability. Do not add to cart yet."""
            }
            "order", "add" -> {
                if (item.isBlank()) return SkillResult.Failure("What do you want to order from Blinkit?")
                """You are in the Blinkit app. Find and add "$item" (quantity: $quantity) to the cart.
STEPS: 1) Tap the Search bar at the top. 2) Type "$item". 3) Browse the search results. 4) Tap the '+' or 'Add' button next to the most relevant product to add it to cart. 5) If quantity is more than 1, tap '+' again until quantity reaches $quantity. 6) Confirm the item was added to cart (check the cart counter at the bottom). Report back what was added and the total price."""
            }
            else ->
                params["goal"] as? String ?: "Do this in Blinkit: $action $item".trim()
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 20)
        return SkillResult.Success(result)
    }
}
