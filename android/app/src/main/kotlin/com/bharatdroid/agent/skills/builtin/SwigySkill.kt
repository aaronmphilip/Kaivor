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
        uiKnowledge = """
Swiggy UI guide:
- Home screen: delivery address shown top left with a down-arrow to change; search icon top right; promotional banners in the middle; category shortcuts (Biryani, Pizza, etc.) below
- Search bar: reads "Search for restaurants and food"; appears after tapping the search icon or the home search field
- Restaurant cards: each card shows restaurant name, cuisine tags, star rating, delivery time (mins), and minimum order amount; scroll vertically to browse
- Restaurant menu: item name on left, price below name, veg (green dot) or non-veg (brown dot) indicator beside name; ADD button on the right of each item
- Cart floating bar: orange/green bar at the very bottom of the menu screen — shows item count and total price; tap it to view full cart
- Cart screen: lists all added items with quantity controls (- qty +) and item prices; "Proceed to checkout" button at the bottom
- Checkout flow: address confirmation → tip option → payment selection (UPI, card, COD) → Place Order
- Filters: after searching, filter chips appear below the search bar — Sort, Rating, Delivery Time, Offers, Pure Veg
""".trimIndent(),
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val query = params["query"] as? String ?: params["goal"] as? String
            ?: return SkillResult.Failure("What food do you want to order?")
        val maxPrice = (params["maxPrice"] as? Number)?.toInt()
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

        if (action in setOf("order", "add_to_cart")) {
            return SkillResult.NeedsConfirmation(
                prompt = "🍽️ *Order via Swiggy*\n\nItem: *$query*${if (maxPrice != null) "\nMax price: ₹$maxPrice" else ""}${if (filterNote.isNotBlank()) "\nFilter: $filterNote" else ""}\n\nReply *YES* to search and add to cart.",
                onConfirm = {
                    val result = agent.executeGoal(runner, goal, maxSteps = 25)
                    SkillResult.Success(result)
                }
            )
        }
        val result = agent.executeGoal(runner, goal, maxSteps = 25)
        return SkillResult.Success(result)
    }
}
