package com.kaivor.agent.skills.builtin

import com.kaivor.agent.skills.*
import kotlinx.coroutines.delay

class BlinkitSkill : Skill {

    override val manifest = SkillManifest(
        id = "blinkit",
        name = "Blinkit Grocery",
        version = "6.0.0",
        description = "Search and order groceries on Blinkit (10-minute delivery) - any grocery task",
        author = "kaivor-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK, Permission.PAYMENT,
        ),
        allowedPackages = setOf("com.grofers.customerapp"),
        exampleParamsHint = """{"action": "order", "item": "amul butter 500g", "quantity": 2}""",
        uiKnowledge = """
Blinkit UI guide:
- Home screen: green header at the top showing delivery address and ETA ("10 minutes"); search bar reads "Search for items"; category tiles (Dairy, Fruits, Snacks, Beverages, etc.) in a grid below
- Search results: product cards with image, brand name, weight/size variant, price (?), and a green ADD button on the right of each card
- Product detail: larger image, name, weight options, price, quantity selector (- 0 +), and "Add to cart" green button
- Cart: floating green cart bar at the bottom shows item count and total; tap it to open the full cart panel; or tap Cart tab in bottom nav
- Checkout: "Proceed to pay" green button at the bottom of cart ? payment options (UPI, card, COD) ? order confirmation
- Category filter: horizontal scroll row of category chips below the search bar
- Out of stock: some products show "OUT OF STOCK" badge - skip and choose the next available variant
""".trimIndent(),
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase() ?: "search"
        val item = params["item"] as? String ?: params["query"] as? String ?: ""
        val quantity = (params["quantity"] as? Number)?.toLong() ?: 1L

        runner.openApp("com.grofers.customerapp")
        runner.waitForApp("com.grofers.customerapp", timeoutMs = 7000)
        delay(200)
        runner.dismissPopups(3)
        delay(100)

        // Direct type into search bar before AI takes over
        val searchDone = if (item.isNotBlank() && action in setOf("search", "order", "add", "find")) {
            val typed = runner.typeInFieldWithHint("Search for items", item)
                || runner.typeInFieldWithHint("Search items", item)
                || runner.typeInFieldWithHint("Search", item)
            if (typed) {
                delay(200)
                runner.pressEnter()
                delay(1800)
                true
            } else {
                val (w, h) = runner.getScreenSize()
                runner.tapAtPoint(w * 0.5f, h * 0.12f)
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
                if (item.isBlank()) return SkillResult.Failure("What do you need from Blinkit?")
                buildString {
                    if (searchDone) {
                        appendLine("You are in Blinkit. Search results for \"$item\" are now showing.")
                        appendLine("STEPS:")
                        appendLine("1. Look at the results on screen.")
                        appendLine("2. Read the top results - product names, weights/sizes, prices, and availability.")
                        appendLine("3. Report what you found - compare similar options if multiple variants exist.")
                    } else {
                        appendLine("You are in Blinkit. Search for \"$item\".")
                        appendLine("STEPS:")
                        appendLine("1. Tap the Search bar at the top.")
                        appendLine("2. Type \"$item\".")
                        appendLine("3. Wait for search results to load.")
                        appendLine("4. Read the top results - product names, weights/sizes, prices, and availability.")
                        appendLine("5. Report what you found - compare similar options if multiple variants exist.")
                    }
                    append("Do NOT add to cart yet.")
                }
            }

            "order", "add" -> {
                if (item.isBlank()) return SkillResult.Failure("What do you want to order from Blinkit?")
                val goal = buildString {
                    if (searchDone) {
                        appendLine("You are in Blinkit. Search results for \"$item\" are on screen.")
                        appendLine("STEPS:")
                        appendLine("1. Find the best matching product for \"$item\" - skip any OUT OF STOCK items.")
                        appendLine("2. Tap the green ADD button next to the correct product.")
                        appendLine("3. If quantity is more than 1, tap + again until quantity = $quantity.")
                        appendLine("4. Check the green cart bar at the bottom - confirm item was added.")
                        appendLine("5. Report: what was added, exact price from screen, quantity, and cart total.")
                    } else {
                        appendLine("You are in Blinkit. Find and add \"$item\" to cart.")
                        appendLine("STEPS:")
                        appendLine("1. Tap the search bar at the top.")
                        appendLine("2. Type \"$item\".")
                        appendLine("3. Wait for results to load.")
                        appendLine("4. Find the best matching product - skip any OUT OF STOCK items.")
                        appendLine("5. Tap the green ADD button next to the correct product.")
                        appendLine("6. If quantity is more than 1, tap + again until quantity = $quantity.")
                        appendLine("7. Check the green cart bar at the bottom - confirm item was added.")
                        appendLine("8. Report: what was added, exact price from screen, quantity, and cart total.")
                    }
                    appendLine("STOP before checkout - do NOT tap 'Proceed to pay'.")
                    append("If you see a weight/size variant pop-up, choose the first available option.")
                }
                return SkillResult.NeedsConfirmation(
                    prompt = "*Order via Blinkit*\n\nItem: *$item*\nQuantity: $quantity\n\nReply *YES* to search and add to cart.",
                    onConfirm = {
                        val result = agent.executeGoal(runner, goal, maxSteps = 60)
                        SkillResult.Success(result)
                    }
                )
            }

            "checkout" ->
                """You are in Blinkit. Proceed to checkout with the current cart.
STEPS:
1. Tap the green cart bar at the bottom to open the cart
2. Review the items - read them out (name, quantity, price)
3. Tap "Proceed to pay"
4. STOP before payment selection - do NOT enter UPI PIN or card details
5. Report: list of items and the total amount"""

            "cart" ->
                """You are in Blinkit. Show what is in the cart.
STEPS:
1. Tap the cart icon or the green cart bar at the bottom
2. Read all items in the cart - name, quantity, price
3. Report the cart total"""

            else ->
                params["goal"] as? String ?: "Do this in Blinkit: $action $item".trim()
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 60)
        return SkillResult.Success(result)
    }
}
