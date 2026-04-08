package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class SwigySkill : Skill {

    override val manifest = SkillManifest(
        id = "swiggy",
        name = "Swiggy Food Order",
        version = "1.0.0",
        description = "Search for food on Swiggy and place an order",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP,
            Permission.READ_SCREEN,
            Permission.TAP,
            Permission.TYPE,
            Permission.SCROLL,
            Permission.NAVIGATE_BACK,
            Permission.PAYMENT,
        ),
        allowedPackages = setOf("in.swiggy.android"),
        exampleParamsHint = """{"query": "biryani", "maxPrice": 200}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val query = params["query"] as? String
            ?: return SkillResult.Failure("Tell me what food you want to order.")
        val maxPrice = (params["maxPrice"] as? Long)?.toInt()

        // Open Swiggy
        runner.openApp("in.swiggy.android")
        if (!runner.waitForApp("in.swiggy.android", timeoutMs = 6000)) {
            return SkillResult.Failure("Swiggy didn't open. Is it installed?")
        }
        delay(1500)

        // Tap search
        val searchTapped = runner.tapByText("Search for restaurants and food")
            || runner.tapByText("Search")
        if (!searchTapped) {
            return SkillResult.Failure("Could not find Swiggy search bar. App layout may have changed.")
        }
        delay(800)

        // Type query
        runner.typeInFieldWithHint("Search", query)
        delay(1500)

        // Read what's on screen
        val screenText = runner.readScreen()

        return if (maxPrice != null) {
            SkillResult.Success(
                "Searched Swiggy for *$query* (max ₹$maxPrice).\n\n" +
                "Here's what I see:\n```\n${screenText.take(600)}\n```\n\n" +
                "Reply with the item name to add it to cart, or 'cancel' to stop."
            )
        } else {
            SkillResult.Success(
                "Searched Swiggy for *$query*.\n\n" +
                "Here's what I see:\n```\n${screenText.take(600)}\n```\n\n" +
                "Reply with the item name to order, or 'cancel' to stop."
            )
        }
    }
}
