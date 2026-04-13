package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.Permission
import com.bharatdroid.agent.skills.Skill
import com.bharatdroid.agent.skills.SkillContext
import com.bharatdroid.agent.skills.SkillManifest
import com.bharatdroid.agent.skills.SkillResult
import kotlinx.coroutines.delay

class ZomatoSkill : Skill {

    override val manifest = SkillManifest(
        id = "zomato",
        name = "Zomato Food Order",
        version = "6.1.0",
        description = "Search and order food from Zomato with filters, cart, and checkout",
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
        allowedPackages = setOf("com.application.zomato"),
        exampleParamsHint = """{"query":"pizza","maxPrice":300,"filter":"rating","action":"order"}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val query = params["query"] as? String ?: params["goal"] as? String
            ?: return SkillResult.Failure("What do you want to order from Zomato?")
        val maxPrice = (params["maxPrice"] as? Long)?.toInt()
        val filter = (params["filter"] as? String)?.trim().orEmpty()
        val action = (params["action"] as? String)?.lowercase() ?: "search"

        runner.openApp("com.application.zomato")
        runner.waitForApp("com.application.zomato", timeoutMs = 6000)
        delay(350)
        runner.dismissPopups(2)
        delay(150)

        val priceNote = if (maxPrice != null) " Prefer options under Rs $maxPrice." else ""
        val filterNote = if (filter.isNotBlank()) " Apply this preference if useful: $filter." else ""

        val goal = if (action == "goal") {
            params["goal"] as? String ?: query
        } else {
            buildString {
                append("In Zomato, search for \"$query\".")
                append(priceNote)
                append(filterNote)
                if (action == "order" || action == "add_to_cart") {
                    append(" Then open the best matching result and add it to the cart.")
                } else {
                    append(" Then open the most relevant result or restaurant.")
                }
            }
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 24)
        return SkillResult.Success(result)
    }
}
