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
        uiKnowledge = "Zomato home has a search bar at top labeled 'Search for restaurant, cuisine or dish'. Tapping it opens a full-screen search. Results show restaurant cards with name, rating (e.g. 4.2★), delivery time (e.g. 28 min), and price range. Tap a restaurant to open its menu. Menu has sections like 'Recommended', 'Bestsellers'. Each item has a green 'ADD' button on the right side. Tapping ADD may show a 'Customise?' bottom sheet — pick first option, tap 'Add to cart'. Common popups to dismiss: 'Allow location', 'Login', 'Rate us', 'Get 50% off' promo banners, 'Refer a friend'. Always dismiss ALL popups before doing anything else.",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val query = params["query"] as? String ?: params["goal"] as? String
            ?: return SkillResult.Failure("What do you want to order from Zomato?")
        val maxPrice = (params["maxPrice"] as? Number)?.toInt()
        val filter = (params["filter"] as? String)?.trim().orEmpty()
        val action = (params["action"] as? String)?.lowercase() ?: "search"

        // ── Pre-execution confirmation for ordering ───────────────────────────
        if (action == "order" || action == "add_to_cart") {
            val priceHint = if (maxPrice != null) "\nMax price: ₹$maxPrice" else ""
            val filterHint = if (filter.isNotBlank()) "\nPreference: $filter" else ""
            return SkillResult.NeedsConfirmation(
                prompt = buildString {
                    appendLine("🍕 *Order via Zomato*")
                    appendLine()
                    appendLine("Item: *$query*$priceHint$filterHint")
                    appendLine()
                    appendLine("I'll find the best-rated restaurant and add *$query* to your cart.")
                    appendLine("I'll stop before payment — you confirm on-screen.")
                    appendLine()
                    append("Reply *YES* to proceed or *NO* to cancel.")
                }.trim(),
                onConfirm = {
                    executeGoal(context, params, query, maxPrice, filter, action)
                },
                onCancel = SkillResult.Cancelled("Order cancelled.")
            )
        }

        return executeGoal(context, params, query, maxPrice, filter, action)
    }

    private suspend fun executeGoal(
        context: SkillContext,
        params: Map<String, Any>,
        query: String,
        maxPrice: Int?,
        filter: String,
        action: String,
    ): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        // ── Continuation support ──────────────────────────────────────────────
        val currentPkg = try { runner.getCurrentPackage() } catch (_: Exception) { "" }
        val alreadyInZomato = currentPkg == "com.application.zomato"

        if (!alreadyInZomato) {
            runner.openApp("com.application.zomato")
            runner.waitForApp("com.application.zomato", timeoutMs = 6000)
            delay(400)
            runner.dismissPopups(2)
            delay(500)
            val zomatoPopupTexts = listOf(
                "Later", "Not now", "Allow", "Skip", "Close", "Cancel",
                "Maybe later", "No thanks", "Got it", "Remind me later",
                "Sign in with Google", "Continue as Guest", "Use without login",
            )
            for (popup in zomatoPopupTexts) {
                if (runner.screenContains(popup)) {
                    runner.tapByText(popup)
                    delay(300)
                }
            }
            runner.dismissPopups(3)
            delay(300)

            // Direct search: type into Zomato's search field before AI takes over
            if (query.isNotBlank() && action != "goal" && action != "continue") {
                val directTyped = runner.typeInFieldWithHint("Search for restaurant", query)
                    || runner.typeInFieldWithHint("Search", query)
                    || runner.typeInFieldWithHint("search", query)
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
        } else {
            runner.dismissPopups(2)
            delay(200)
        }

        val priceNote = if (maxPrice != null) " Prefer options under Rs $maxPrice." else ""
        val filterNote = if (filter.isNotBlank()) " Apply this preference if useful: $filter." else ""

        val customizeNote = buildString {
            appendLine()
            appendLine("⚠️  ZOMATO CUSTOMIZATION POPUPS (VERY IMPORTANT):")
            appendLine("When you tap ADD on a menu item, Zomato often shows a 'Customise?' or")
            appendLine("'Choose variant' bottom sheet from the bottom of the screen.")
            appendLine("DO NOT get stuck there. Handle it like this:")
            appendLine("- If you see a customization sheet: select the FIRST option listed")
            appendLine("- Then tap 'Add Item', 'Add to cart', or the green button to confirm")
            appendLine("- If you see 'Add-ons' or extras: tap 'Skip' or the 'Add Item' button directly")
            appendLine("- If a 'Repeat Last Order?' popup appears: tap 'New Customisation'")
            appendLine("After adding the item to cart, report what was added and what options were selected.")
        }

        val goal = when {
            action == "goal" -> params["goal"] as? String ?: query

            action == "continue" || alreadyInZomato && action != "goal" -> buildString {
                append("TASK: Continue from the current Zomato screen. $query\n\n")
                append("⚠️ You are ALREADY inside Zomato. Do NOT go back to the home screen.\n")
                append("Read what is currently visible on screen and continue from there.\n\n")
                if (action == "order" || action == "add_to_cart") {
                    append("If you see a menu/restaurant: find \"$query\" and tap ADD.\n")
                    append(customizeNote)
                    append("\nSTOP before payment. Report what you added to cart.")
                } else {
                    append("Complete the user's request: $query\n")
                    append(customizeNote)
                }
            }

            action == "order" || action == "add_to_cart" -> buildString {
                append("TASK: Order \"$query\" from Zomato.\n\n")
                if (alreadyInZomato) {
                    append("⚠️ Zomato is already open. Continue from the current screen.\n\n")
                } else {
                    append("Search results may already be visible. Check before searching again.\n\n")
                }
                append("STEPS:\n")
                append("1. If no results yet: find the search bar and type \"$query\"\n")
                append("2. Scroll through the restaurant list\n")
                append(priceNote).append(filterNote).appendLine()
                append("   🧠 SMART PICK: Choose restaurant with rating ≥ 4.0 AND delivery ≤ 35 min.\n")
                append("   If multiple qualify, pick highest rating. Mention the trade-off if none qualify.\n")
                append("3. TAP the best matching restaurant to open its menu\n")
                append("4. Scroll the menu to find \"$query\"\n")
                append("5. TAP the ADD button (green '+' or 'ADD' text) next to the item\n")
                append(customizeNote)
                append("\n6. After item is added: tap the cart button or 'View Cart'\n")
                append("7. STOP before payment — show the cart summary\n\n")
                append("STRICT RULES:\n")
                append("- DO NOT press back through results — scroll instead\n")
                append("- DO NOT tap mic/voice icons\n")
                append("- DO NOT enter payment details")
            }

            else -> buildString {
                append("TASK: Find \"$query\" on Zomato.\n\n")
                if (alreadyInZomato) {
                    append("⚠️ Zomato is already open. Read current screen and continue.\n\n")
                }
                append("STEPS:\n")
                append("1. If no results visible: search for \"$query\"\n")
                append("2. Scroll through restaurant/food results\n")
                append(priceNote).append(filterNote).appendLine()
                append("3. Tap the best matching restaurant\n")
                append("4. Find \"$query\" on their menu\n")
                append(customizeNote)
                append("\nDO NOT press back repeatedly. DO NOT go to home. Stay in Zomato.")
            }
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 28)
        return SkillResult.Success(result)
    }
}
