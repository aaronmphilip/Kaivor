package com.kaivor.agent.skills.builtin

import com.kaivor.agent.skills.*
import kotlinx.coroutines.delay

class ZeptoSkill : Skill {

    override val manifest = SkillManifest(
        id = "zepto",
        name = "Zepto Grocery Order",
        version = "6.0.0",
        description = "Search and order groceries from Zepto (10-minute delivery) - any grocery task",
        author = "kaivor-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK, Permission.PAYMENT,
        ),
        allowedPackages = setOf("com.zepto.app"),
        exampleParamsHint = """{"action": "order", "item": "milk 1 litre", "quantity": 2}""",
        uiKnowledge = """
Zepto UI guide:
- Home screen: purple-themed header with delivery address and countdown ETA; search bar at the top reads "Search products"
- Category tiles: row of category shortcuts below the search bar - Fruits & Vegetables, Dairy, Snacks, Beverages, Personal Care, etc.
- Product cards: product image, name, weight/size, price (?), ADD button (purple) on each card
- Search results: grid of product cards after typing; tap a card for product details
- Product detail: larger image, full name, weight options, price, quantity selector (- qty +), "Add to Cart" purple button
- Cart: bottom bar shows item count and "View Cart" purple button; tap to open full cart panel
- Cart panel: lists all added items with name, quantity controls, price; "Proceed to Checkout" button at the bottom
- Checkout flow: address confirmation ? payment selection (UPI, card, COD) ? order placed
- Filters: after search, filter chips appear - Brands, Price Range, Discount, Category
- Out of stock: shown as "SOLD OUT" badge - choose alternate variant or brand
""".trimIndent(),
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase() ?: "search"
        val item = params["item"] as? String ?: params["query"] as? String ?: ""
        val quantity = (params["quantity"] as? Number)?.toLong() ?: 1L

        runner.openApp("com.zepto.app")
        runner.waitForApp("com.zepto.app", timeoutMs = 7000)
        delay(200)
        runner.dismissPopups(3)
        delay(100)

        // Direct type into search bar
        val searchDone = if (item.isNotBlank() && action in setOf("search", "order", "add", "find")) {
            val typed = runner.typeInFieldWithHint("Search products", item)
                || runner.typeInFieldWithHint("Search", item)
            if (typed) {
                delay(200)
                runner.pressEnter()
                delay(1800)
                true
            } else {
                val (w, h) = runner.getScreenSize()
                runner.tapAtPoint(w * 0.5f, h * 0.1f)
                delay(400)
                runner.typeReliably(item)
                delay(200)
                runner.pressEnter()
                delay(1800)
                true
            }
        } else false

        val goal = when (action) {
            "search", "find" -> {
                if (item.isBlank()) return SkillResult.Failure("What grocery item do you need?")
                """You are in Zepto. ${if (searchDone) "Search results for \"$item\" are now showing." else "Search for \"$item\"."}
STEPS:
${if (!searchDone) "1. Tap the Search bar at the top\n2. Type \"$item\"\n3. Wait for results" else "1. Look at the results on screen"}
${if (searchDone) "2." else "4."} Compare the top 3 results - product names, weights/sizes, prices, availability
${if (searchDone) "3." else "5."} Report the best option - which brand/size offers the best value, and if it's in stock
Do NOT add to cart yet"""
            }

            "order", "add" -> {
                if (item.isBlank()) return SkillResult.Failure("What do you want to order from Zepto?")
                val goal = """You are in Zepto. ${if (searchDone) "Search results for \"$item\" are on screen." else "Find and add \"$item\" to cart."}
STEPS:
${if (!searchDone) "1. Tap the search bar\n2. Type \"$item\"\n3. Wait for results\n4." else "1."} Find the best matching product - skip any SOLD OUT items
${if (searchDone) "2." else "5."} Tap ADD next to the correct product (or tap the product to open its detail page, then tap Add to Cart)
${if (searchDone) "3." else "6."} If quantity > 1, tap + until you reach $quantity
${if (searchDone) "4." else "7."} Check the purple cart bar at the bottom - confirm item was added
${if (searchDone) "5." else "8."} Report: what was added, weight, price, quantity, and cart total
STOP before checkout - do NOT tap Proceed to Checkout
If a variant picker appears (size/weight), choose the best available option"""
                return SkillResult.NeedsConfirmation(
                    prompt = "*Order via Zepto*\n\nItem: *$item*\nQuantity: $quantity\n\nReply *YES* to search and add to cart.",
                    onConfirm = {
                        val result = agent.executeGoal(runner, goal, maxSteps = 60)
                        SkillResult.Success(result)
                    }
                )
            }

            "checkout" ->
                """You are in Zepto. Proceed to checkout with the current cart.
STEPS:
1. Tap the purple "View Cart" bar at the bottom to open the cart
2. Review all items - read name, quantity, price
3. Tap "Proceed to Checkout"
4. STOP before payment - do NOT enter UPI PIN or card details
5. Report: list of items, subtotal, and delivery fee"""

            "cart" ->
                """You are in Zepto. Show what is in the cart.
STEPS:
1. Tap the "View Cart" button at the bottom
2. Read all items - name, quantity, price
3. Report the cart total and delivery estimate"""

            else ->
                params["goal"] as? String ?: "Do this in Zepto: $action $item".trim()
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 60)
        return SkillResult.Success(result)
    }
}
