package com.kaivor.agent.skills.builtin

import com.kaivor.agent.skills.*
import kotlinx.coroutines.delay

class FlipkartSkill : Skill {

    override val manifest = SkillManifest(
        id = "flipkart",
        name = "Flipkart Shopping",
        version = "5.0.0",
        description = "Search products, check prices, track orders on Flipkart",
        author = "kaivor-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK, Permission.PAYMENT,
        ),
        allowedPackages = setOf("com.flipkart.android"),
        exampleParamsHint = """{"action": "search", "query": "boAt Airdopes 141", "maxPrice": 1500}""",
        uiKnowledge = """
Flipkart UI guide:
- Home screen: blue header at the top; search bar reads "Search for Products, Brands and More"; cart icon (bag) at the top right
- Search results: product list/grid - each card shows product image, title, price (?), discount percentage (e.g. "40% off"), star rating, and number of reviews
- Filter / Sort bar: appears just below search results - "Sort" button and "Filter" button; tap to apply filters like Price Range, Brand, Rating, Discount
- Product page: image carousel at top; full product title; price with strikethrough MRP and discount; "ADD TO CART" button (white with blue border); "BUY NOW" button (solid yellow); scroll down for specs, reviews, seller info
- Cart: tap the bag icon at the top right ? lists all added items with quantity selectors and prices; "PLACE ORDER" blue button at the bottom
- Order flow: after PLACE ORDER ? address selection ? payment (UPI/card/EMI/COD) ? Order Confirmed screen
- My Orders: hamburger or profile menu ? "My Orders" ? list of past orders with product name, status, and return/review options
""".trimIndent(),
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val query = params["query"] as? String ?: params["goal"] as? String ?: ""
        val maxPrice = (params["maxPrice"] as? Number)?.toInt()
        val action = (params["action"] as? String)?.lowercase() ?: "search"

        runner.openApp("com.flipkart.android")
        runner.waitForApp("com.flipkart.android", timeoutMs = 6000)
        delay(600)
        runner.dismissPopups(2)
        delay(200)

        // Direct search: type into search field before AI takes over
        // Run for all shopping actions - buy/add_to_cart need the search done first too
        val isShoppingAction = action in setOf("search", "buy", "add_to_cart", "order", "purchase")
        if (isShoppingAction && query.isNotBlank()) {
            val directTyped = runner.typeInFieldWithHint("Search for Products", query)
                || runner.typeInFieldWithHint("Search", query)
            if (directTyped) {
                delay(200)
                runner.pressEnter()
                delay(1500)
            } else {
                val (w, h) = runner.getScreenSize()
                runner.tapAtPoint(w * 0.35f, h * 0.07f)
                delay(400)
                runner.typeReliably(query)
                delay(200)
                runner.pressEnter()
                delay(1500)
            }
        }

        val goal = when (action) {
            "goal" -> params["goal"] as? String ?: query

            "buy", "add_to_cart", "order", "purchase" -> {
                val goal = buildString {
                    append("TASK: Find \"$query\" on Flipkart and ADD IT TO CART.\n\n")
                    append("Search results are already on screen. Do not re-search.\n\n")
                    append("STEPS:\n")
                    append("1. Scroll through the product list\n")
                    if (maxPrice != null) append("2. Find a product UNDER Rs $maxPrice\n")
                    else append("2. Find the best rated product (4+ stars preferred)\n")
                    append("3. TAP the product card to open its detail page\n")
                    append("4. On the product page: scroll to find 'Add to Cart' button\n")
                    append("5. TAP 'Add to Cart'\n")
                    append("6. STOP after cart confirmation - report what was added\n\n")
                    append("STOP BEFORE CHECKOUT. Do NOT tap 'Buy Now' or enter payment details.\n")
                    append("If a variant/size picker appears, tap the FIRST option.\n")
                    append("DO NOT press back - you will lose the product page.")
                }
                return SkillResult.NeedsConfirmation(
                    prompt = "*Add to Cart on Flipkart*\n\nItem: *$query*${if (maxPrice != null) "\nMax price: Rs $maxPrice" else ""}\n\nI'll search, find the best match, and add it to your cart.\n\nReply *YES* to proceed.",
                    onConfirm = {
                        val result = agent.executeGoal(runner, goal, maxSteps = 55)
                        SkillResult.Success(result)
                    }
                )
            }

            else -> buildString {
                append("TASK: Find the BEST \"$query\" on Flipkart.\n\n")
                if (isShoppingAction) append("Search already submitted. You should see results.\n\n")
                append("STEPS:\n")
                append("1. Scroll through results to compare options\n")
                if (maxPrice != null) append("2. Filter by price under Rs $maxPrice\n")
                append("3. Look for high ratings (4+ stars) and good review count\n")
                append("4. Tap the best match to see details\n")
                append("5. On product page: scroll to read reviews, specs, features\n")
                append("6. Report findings - name, price, rating, key features\n\n")
                append("DO NOT press back repeatedly. DO NOT go to home screen.\n")
                append("DO NOT tap mic/camera/voice icons. Stay on results page.")
            }
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 55)
        return SkillResult.Success(result)
    }
}
