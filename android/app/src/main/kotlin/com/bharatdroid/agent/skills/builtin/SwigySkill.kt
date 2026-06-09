package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class SwigySkill : Skill {

    override val manifest = SkillManifest(
        id = "swiggy",
        name = "Swiggy Food Order",
        version = "7.0.0",
        description = "Search and order food from Swiggy - any order, filter, or delivery task",
        author = "bharatclaw-team",
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
- Cart floating bar: orange/green bar at the very bottom of the menu screen - shows item count and total price; tap it to view full cart
- Cart screen: lists all added items with quantity controls (- qty +) and item prices; "Proceed to checkout" button at the bottom
- Checkout flow: address confirmation ? tip option ? payment selection (UPI, card, COD) ? Place Order
- Filters: after searching, filter chips appear below the search bar - Sort, Rating, Delivery Time, Offers, Pure Veg
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

        // Tap search bar first (it opens a search screen), then type
        if (query.isNotBlank() && action != "goal") {
            val searchEl = runner.getClickableElements().firstOrNull { el ->
                val t = (el.text + el.hint + el.contentDescription).lowercase()
                (t.contains("search") || t.contains("restaurant") || t.contains("food")) &&
                    !t.contains("voice") && !t.contains("mic")
            }
            if (searchEl != null) {
                runner.tapAtPoint(searchEl.centerX.toFloat(), searchEl.centerY.toFloat())
                delay(600) // wait for search screen to open
            }
            val typed = runner.typeInFieldWithHint("Search for", query)
                || runner.typeInFieldWithHint("Search", query)
                || runner.typeReliably(query)
            if (typed) {
                delay(300)
                runner.pressEnter()
                delay(1800)
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
                appendLine("TASK: ${if (action == "order" || action == "add_to_cart") "Order" else "Find"} \"$query\"$priceNote$filterNote on Swiggy.")
                appendLine()
                appendLine("CRITICAL: Search results may already be visible. DO NOT tap the search bar again.")
                appendLine()
                appendLine("STEPS:")
                appendLine("1. READ what is on screen RIGHT NOW:")
                appendLine("   - If you see a LIST OF RESTAURANTS ? go to step 2.")
                appendLine("   - If you see a MENU inside a restaurant ? go to step 4.")
                appendLine("   - ONLY if the Swiggy home screen shows with no results ? tap search and type \"$query\".")
                appendLine("2. From the restaurant list: scroll to find the best match.")
                if (filter.isNotBlank()) appendLine("   Apply filter: $filterNote")
                if (maxPrice != null) appendLine("   Look for options$priceNote")
                appendLine("   SMART PICK: rating = 4.0, delivery = 35 min. Pick highest rated if multiple qualify.")
                appendLine("3. TAP the restaurant CARD (not search bar) to open the menu.")
                appendLine("4. Scroll the menu to find \"$query\".")
                if (action == "order" || action == "add_to_cart") {
                    appendLine("5. Tap ADD button next to \"$query\".")
                    appendLine("6. If a customise popup appears: select first option ? tap Add Item.")
                    appendLine("7. Tap the cart bar at bottom ? Proceed to checkout.")
                    appendLine("8. STOP before payment - show the cart summary.")
                } else {
                    appendLine("5. Read the menu item details and price.")
                }
                appendLine()
                appendLine("STRICT RULES:")
                appendLine("- NEVER tap the search bar if results are already visible")
                appendLine("- NEVER press back repeatedly - scroll instead")
                appendLine("- NEVER tap mic/voice icons")
                appendLine("- NEVER enter payment details")
            }
        }

        if (action in setOf("order", "add_to_cart")) {
            return SkillResult.NeedsConfirmation(
                prompt = "*Order via Swiggy*\n\nItem: *$query*${if (maxPrice != null) "\nMax price: Rs $maxPrice" else ""}${if (filterNote.isNotBlank()) "\nFilter: $filterNote" else ""}\n\nReply *YES* to search and add to cart.",
                onConfirm = {
                    val result = agent.executeGoal(runner, goal, maxSteps = 65)
                    SkillResult.Success(result)
                }
            )
        }
        val result = agent.executeGoal(runner, goal, maxSteps = 65)
        return SkillResult.Success(result)
    }
}
