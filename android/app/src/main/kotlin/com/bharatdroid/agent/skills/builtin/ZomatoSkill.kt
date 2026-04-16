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
        val maxPrice = (params["maxPrice"] as? Number)?.toInt()
        val filter = (params["filter"] as? String)?.trim().orEmpty()
        val action = (params["action"] as? String)?.lowercase() ?: "search"

        runner.openApp("com.application.zomato")
        runner.waitForApp("com.application.zomato", timeoutMs = 6000)
        delay(350)
        runner.dismissPopups(2)
        delay(150)

        // Direct search: type into Zomato's search field before AI takes over
        if (query.isNotBlank() && action != "goal") {
            val directTyped = runner.typeInFieldWithHint("Search for restaurant", query)
                || runner.typeInFieldWithHint("Search", query)
                || runner.typeInFieldWithHint("search", query)
            if (directTyped) {
                delay(200)
                runner.pressEnter()
                delay(1500)
            } else {
                // Tap the search icon/bar — Zomato has a search icon at the top
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

        val priceNote = if (maxPrice != null) " Prefer options under Rs $maxPrice." else ""
        val filterNote = if (filter.isNotBlank()) " Apply this preference if useful: $filter." else ""

        val goal = if (action == "goal") {
            params["goal"] as? String ?: query
        } else {
            buildString {
                append("TASK: Find \"$query\" on Zomato.\n\n")
                if (action != "goal") append("Search may already be submitted. Check if results are visible.\n\n")
                append("STEPS:\n")
                append("1. If no results visible, find the search bar and type \"$query\"\n")
                append("2. Scroll through restaurant/food results\n")
                append(priceNote)
                append(filterNote)
                append("\n3. Tap the best matching restaurant\n")
                append("4. Find \"$query\" on their menu\n")
                if (action == "order" || action == "add_to_cart") {
                    append("5. Tap ADD to add to cart\n")
                    append("6. Open cart and proceed — STOP before payment\n")
                }
                append("\nDO NOT press back repeatedly. DO NOT go to home screen.\n")
                append("DO NOT tap mic/voice icons. Stay in Zomato.")
            }
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 24)
        return SkillResult.Success(result)
    }
}
