package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.Permission
import com.bharatdroid.agent.skills.Skill
import com.bharatdroid.agent.skills.SkillContext
import com.bharatdroid.agent.skills.SkillManifest
import com.bharatdroid.agent.skills.SkillResult
import kotlinx.coroutines.delay

class AmazonSkill : Skill {

    override val manifest = SkillManifest(
        id = "amazon",
        name = "Amazon Shopping",
        version = "6.1.0",
        description = "Search products, add to cart, and track orders on Amazon India",
        author = "bharatclaw-team",
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
        allowedPackages = setOf("in.amazon.mShop.android.shopping"),
        exampleParamsHint = """{"action":"search","query":"noise cancelling headphones","maxPrice":2000}""",
        uiKnowledge = """
Amazon India UI guide:
- Home screen: orange header at the top; search bar reads "Search Amazon.in" in the center of the header; cart icon (shopping trolley) at the top right; mic icon and camera icon are on the FAR RIGHT of the search bar — do NOT tap them
- Search results: vertical list/grid of product cards — each shows product image, title (truncated), price (?), star rating, review count, Prime badge (blue), and sometimes a discount label
- Product page: image carousel at the top (swipe left/right); full title; price in large text; "Add to Cart" yellow button; "Buy Now" orange button; scroll down for description, reviews, specifications
- Cart: tap the trolley icon at the top right ? shows list of items with quantities (- qty +) and prices; "Proceed to Buy" yellow button at the bottom
- Address page: appears after Proceed to Buy — shows saved addresses; "Deliver to this address" button
- Payment page: shows saved cards/UPI/COD options; "Place your order" button at the bottom
- Orders: hamburger menu (three lines) top left ? "Your Orders" ? list of past orders with product name, date, status (Delivered/Shipped)
- Filters: after search, "Sort" and filter options appear at the top of results — Avg. Customer Review, Price: Low to High, Featured, etc.
""".trimIndent(),
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase() ?: "search"
        val query = params["query"] as? String ?: params["goal"] as? String ?: ""
        val maxPrice = (params["maxPrice"] as? Number)?.toInt()
        val filter = params["filter"] as? String ?: ""

        runner.openApp("in.amazon.mShop.android.shopping")
        runner.waitForApp("in.amazon.mShop.android.shopping", timeoutMs = 7000)
        delay(350)
        runner.dismissPopups(2)
        delay(150)

        // For search/buy tasks: directly type into search field BEFORE delegating to AI
        // This skips the mic/camera/voice confusion entirely
        // Run for ALL shopping actions — "buy", "add_to_cart", "order", "purchase" all need search first
        val isShoppingAction = action in setOf("search", "buy", "add_to_cart", "order", "purchase")
        if (isShoppingAction && query.isNotBlank()) {
            // Try to find and type directly into the Amazon search bar
            // Try multiple hint variants — Amazon India uses "Search Amazon.in"
            val directTyped = runner.typeInFieldWithHint("Search Amazon.in", query)
                || runner.typeInFieldWithHint("Search Amazon", query)
                || runner.typeInFieldWithHint("Search", query)
            if (directTyped) {
                delay(200)
                runner.pressEnter()
                delay(1800) // wait for results to fully load
            } else {
                // Fallback: tap LEFT side of search bar (35% from left) — the mic/camera icons
                // are on the FAR RIGHT of the bar. Tapping left avoids them entirely.
                val (w, h) = runner.getScreenSize()
                runner.tapAtPoint(w * 0.35f, h * 0.07f)
                delay(400)
                runner.typeReliably(query)
                delay(200)
                runner.pressEnter()
                delay(1800)
            }
            // Tap center of screen to dismiss keyboard and unfocus search bar
            // This prevents the AI from accidentally re-tapping the search area
            val (w, h) = runner.getScreenSize()
            runner.tapAtPoint(w * 0.5f, h * 0.4f)
            delay(400)
        }

        val goal = when (action) {
            "orders", "track" -> "In Amazon, open the Orders section and show recent orders."
            "cart" -> "In Amazon, open the shopping cart and show what is inside."
            "goal" -> params["goal"] as? String ?: query

            // ADD TO CART / BUY: search already done above — now find product and tap Add to Cart
            "buy", "add_to_cart", "order", "purchase" -> {
                val goal = buildString {
                    append("TASK: Find \"$query\" on Amazon and ADD IT TO CART.\n\n")
                    append("?? Search results for \"$query\" are ALREADY ON SCREEN. Do not re-search.\n")
                    append("?? DO NOT tap the search bar, camera (??), or mic (??) icons at the top.\n\n")
                    append("STEPS:\n")
                    append("1. SCROLL DOWN through the product list to see results\n")
                    if (maxPrice != null) append("2. Look for a product UNDER Rs $maxPrice — check the price shown on each card\n")
                    else append("2. Look for a well-rated product (4+ stars, good review count)\n")
                    append("3. TAP the best product card to open its detail page\n")
                    append("4. On the detail page: scroll down until you see the YELLOW 'Add to Cart' button\n")
                    append("5. TAP 'Add to Cart' — this is the required action\n")
                    append("6. Wait for confirmation, then say 'Added [product name] to cart'\n\n")
                    append("?? STOP after 'Add to Cart' succeeds. Do NOT tap 'Proceed to Buy'.\n")
                    append("?? Do NOT enter payment or address details.\n")
                    append("?? If a variant picker appears (size/color), tap the FIRST option shown.\n")
                    append("?? Do NOT press back — you will lose the product page.")
                }
                return SkillResult.NeedsConfirmation(
                    prompt = "?? *Add to Cart on Amazon*\n\nItem: *$query*${if (maxPrice != null) "\nMax price: ?$maxPrice" else ""}\n\nI'll search, find the best match, and add it to your cart.\n\nReply *YES* to proceed.",
                    onConfirm = {
                        val result = agent.executeGoal(runner, goal, maxSteps = 60)
                        SkillResult.Success(result)
                    }
                )
            }

            "search" -> buildString {
                append("TASK: Find the BEST \"$query\" on Amazon.\n\n")
                append("?? SEARCH IS ALREADY DONE. Product results are NOW on screen.\n")
                append("?? DO NOT tap the search bar, camera icon, or mic icon at the top.\n")
                append("   The camera (??) opens photo search. The mic (??) opens voice search. NEVER tap them.\n")
                append("   The search for \"$query\" is finished. Just scroll the product list below.\n\n")
                append("STEPS:\n")
                append("1. SCROLL DOWN to see the list of products in the results\n")
                if (maxPrice != null) append("2. Look for products under Rs $maxPrice\n")
                append("3. Check ratings (4+ stars preferred) and review count\n")
                append("4. Tap on the best matching product to see its details\n")
                append("5. On the product page: scroll down to read reviews, features, specifications\n")
                append("6. Report what you found — product name, price, rating, key features\n\n")
                append("STRICT RULES:\n")
                append("- DO NOT touch the search bar at the top — results are already showing\n")
                append("- DO NOT press back — you will lose the results\n")
                append("- DO NOT go to home screen or open other apps")
            }
            else -> buildString {
                append("TASK: \"$query\" on Amazon.\n\n")
                append("?? NEVER tap the camera (??) or mic (??) icons next to the search bar.\n")
                append("If search is needed: tap the WIDE TEXT FIELD in the center of the search bar only.\n")
                append("After searching: scroll results, find best match, tap to view details.\n")
                if (maxPrice != null) append("Price limit: Rs $maxPrice\n")
            }
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 60)
        return SkillResult.Success(result)
    }
}
