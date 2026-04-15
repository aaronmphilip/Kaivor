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

        // Direct search: type into Swiggy's search field before AI takes over
        if (query.isNotBlank() && action != "goal") {
            val directTyped = runner.typeInFieldWithHint("Search for", query)
                || runner.typeInFieldWithHint("Search", query)
            if (directTyped) {
                delay(200)
                runner.pressEnter()
                delay(1500)
            } else {
                val searchEl = runner.getClickableElements().firstOrNull { el ->
                    val t = (el.text + el.hint + el.contentDescription).lowercase()
                    (t.contains("search") || t.contains("find")) && !t.contains("voice") && !t.contains("mic")
                }
                if (searchEl != null) {
                    runner.tapAtPoint(searchEl.centerX.toFloat(), searchEl.centerY.toFloat())
                    delay(400)
                    runner.typeReliably(query)
                    delay(200)
                    runner.pressEnter()
                    delay(1500)
                }
            }
        }

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
            buildString {
                appendLine("TASK: Find \"$query\"$priceNote$filterNote on Swiggy.")
                appendLine()
                appendLine("Search may already be submitted. Check if results are visible.")
                appendLine()
                appendLine("STEPS:")
                appendLine("1. If no results visible, find the search bar and type \"$query\"")
                appendLine("2. Scroll through restaurant results")
                if (filter.isNotBlank()) appendLine("3. Apply filter: $filterNote")
                if (maxPrice != null) appendLine("3. Look for options$priceNote")
                appendLine("4. Tap the best restaurant")
                appendLine("5. Find \"$query\" on their menu")
                if (action == "order" || action == "add_to_cart") {
                    appendLine("6. Tap ADD to add to cart")
                    appendLine("7. Open cart → Proceed — STOP before payment")
                }
                appendLine()
                appendLine("DO NOT press back repeatedly. DO NOT go to home screen.")
                appendLine("DO NOT tap mic/voice icons. Stay in Swiggy.")
            }
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 25)
        return SkillResult.Success(result)
    }
}
