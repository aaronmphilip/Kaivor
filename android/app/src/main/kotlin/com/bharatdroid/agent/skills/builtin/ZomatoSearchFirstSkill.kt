package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.ScreenElement
import com.bharatdroid.agent.skills.Permission
import com.bharatdroid.agent.skills.SandboxedRunner
import com.bharatdroid.agent.skills.Skill
import com.bharatdroid.agent.skills.SkillContext
import com.bharatdroid.agent.skills.SkillManifest
import com.bharatdroid.agent.skills.SkillResult
import kotlinx.coroutines.delay

class ZomatoSearchFirstSkill : Skill {

    override val manifest = SkillManifest(
        id = "zomato",
        name = "Zomato Food Order",
        version = "6.2.0",
        description = "Search, compare, and order food from Zomato with smarter follow-up handling",
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
        exampleParamsHint = """{"query":"paneer momos","maxPrice":250,"action":"search"}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val query = params["query"] as? String ?: params["goal"] as? String
            ?: return SkillResult.Failure("What do you want to order from Zomato?")
        val maxPrice = (params["maxPrice"] as? Number)?.toInt()
        val filter = (params["filter"] as? String)?.trim().orEmpty()
        val action = (params["action"] as? String)?.lowercase() ?: "search"

        val currentPkg = try { runner.getCurrentPackage() } catch (_: Exception) { "" }
        val alreadyInZomato = currentPkg == "com.application.zomato"

        if (!alreadyInZomato) {
            runner.openApp("com.application.zomato")
            runner.waitForApp("com.application.zomato", timeoutMs = 6000)
            delay(500)
        }

        runner.dismissPopups(2)
        delay(200)
        dismissZomatoSpecificPopups(runner)
        delay(200)

        val shouldContinueFromCurrentScreen =
            action == "continue" || (alreadyInZomato && looksLikeSelectionFollowUp(query))

        val shouldPrimeSearch =
            query.isNotBlank() && action != "goal" && !shouldContinueFromCurrentScreen

        if (shouldPrimeSearch) {
            primeGlobalSearch(
                runner = runner,
                query = query,
                allowGenericFallback = true,
            )
        }

        val priceNote = if (maxPrice != null) {
            "Keep the shortlist around or under Rs $maxPrice when possible."
        } else {
            ""
        }
        val filterNote = if (filter.isNotBlank()) {
            "Use this preference if it helps: $filter."
        } else {
            ""
        }

        val customizeNote = buildString {
            appendLine("If a customization sheet appears:")
            appendLine("- choose the first sensible default or first required option")
            appendLine("- tap Add Item, Add to cart, or the green confirm button")
            appendLine("- skip extras unless the user explicitly asked for them")
            appendLine("- if Repeat Last Order appears, choose New Customisation")
        }

        val goal = when {
            action == "goal" -> params["goal"] as? String ?: query

            shouldContinueFromCurrentScreen -> buildString {
                appendLine(zomatoUiContext)
                appendLine()
                appendLine("TASK: Continue the current Zomato flow and order the user's chosen option: \"$query\".")
                appendLine()
                appendLine("You are already inside Zomato. Continue from the CURRENT screen.")
                appendLine("Interpret the user's choice using what is visible now.")
                appendLine("Examples: first one, second one, cheaper one, that one, go ahead, place order.")
                appendLine()
                appendLine("STEPS:")
                appendLine("1. If search results are visible, select the result that best matches \"$query\".")
                appendLine("2. If a restaurant page or menu is open, find the matching item.")
                appendLine("3. Tap ADD next to the chosen item.")
                appendLine(customizeNote.trim())
                appendLine("4. Open View Cart or the cart button.")
                appendLine("5. If a continue, proceed, or place-order button appears before payment, tap it.")
                appendLine("6. STOP when the order is placed OR when the final payment screen or pay button is visible.")
                appendLine()
                appendLine("STRICT RULES:")
                appendLine("- Do NOT restart from the home screen unless the current screen is unusable.")
                appendLine("- Do NOT search inside a restaurant menu unless you already selected the correct result.")
                appendLine("- Do NOT enter card or UPI details manually.")
                appendLine("- Do NOT tap mic or voice icons.")
                appendLine()
                appendLine("FINAL REPLY:")
                appendLine("- If ordered: Ordered <item> from <restaurant>.")
                appendLine("- Otherwise: Added <item> from <restaurant> to cart and reached the final order or payment step.")
            }

            action == "search" || action == "order" || action == "add_to_cart" -> buildString {
                appendLine(zomatoUiContext)
                appendLine()
                appendLine("TASK: Search Zomato for \"$query\" and shortlist the best options.")
                appendLine()
                if (shouldPrimeSearch) {
                    appendLine("IMPORTANT: The search for \"$query\" has already been submitted. Results are loading or already visible on screen.")
                    appendLine("Do NOT open the search bar or type again. Scroll down and read the visible results directly.")
                } else {
                    appendLine("ALWAYS start with APP-LEVEL search for \"$query\".")
                    appendLine("Do NOT inspect a restaurant menu first.")
                    appendLine("If you are inside a restaurant, move to the main Zomato search UI before comparing options.")
                    appendLine("If search results for \"$query\" are already visible, do not re-type the search.")
                }
                appendLine()
                appendLine("STEPS:")
                appendLine("1. Use the Zomato search bar or search icon and search for \"$query\".")
                appendLine("2. Stay on the search results list first and compare visible matches.")
                appendLine("3. Prefer visible dish results and visible prices on the results page.")
                if (priceNote.isNotBlank()) appendLine(priceNote)
                if (filterNote.isNotBlank()) appendLine(filterNote)
                appendLine("4. If price is not visible on the results cards, open only the single best match to inspect the matching item's price, then stop.")
                appendLine("5. Report 3 to 5 strong options with item name, restaurant name, and visible price.")
                appendLine("6. STOP and ask the user which one to order.")
                appendLine()
                appendLine("STRICT RULES:")
                appendLine("- Do NOT add anything to cart yet.")
                appendLine("- Do NOT open multiple restaurant menus.")
                appendLine("- Do NOT tap mic or voice icons.")
                appendLine("- Do NOT go to payment.")
                appendLine()
                appendLine("FINAL REPLY — read EXACT values from the screen, do NOT use placeholders or invent prices:")
                appendLine("List 3–5 options, one per line, using this format:")
                appendLine("1. [item name] from [restaurant name] — Rs [exact price from screen]")
                appendLine("2. [item name] from [restaurant name] — Rs [exact price from screen]")
                appendLine("End with: 'Tell me which one to order.'")
                appendLine("Only include items and prices you actually SEE on screen. Never guess.")
            }

            else -> buildString {
                appendLine("TASK: Handle this Zomato request: \"$query\".")
                appendLine("Use app-level search first unless the user is clearly continuing from current results.")
                appendLine("Stay inside Zomato, avoid mic icons, and do not enter payment details manually.")
            }
        }

        val maxSteps = if (shouldContinueFromCurrentScreen) 32 else 28
        val result = agent.executeGoal(runner, goal, maxSteps = maxSteps)
        return SkillResult.Success(result)
    }

    private suspend fun primeGlobalSearch(
        runner: SandboxedRunner,
        query: String,
        allowGenericFallback: Boolean,
    ): Boolean {
        val typedDirectly = tryTypeIntoSearchField(
            runner = runner,
            query = query,
            allowGenericFallback = allowGenericFallback,
        )

        if (typedDirectly) {
            finishSearchSubmit(runner)
            return true
        }

        findSearchEntryPoint(runner.getClickableElements(), allowGenericFallback)?.let { searchElement ->
            runner.tapAtPoint(searchElement.centerX.toFloat(), searchElement.centerY.toFloat())
            runner.waitForScreenChange(timeoutMs = 2200)
            delay(350)
        }

        val typedAfterOpeningSearch = tryTypeIntoSearchField(
            runner = runner,
            query = query,
            allowGenericFallback = true,
        )
        if (typedAfterOpeningSearch) {
            finishSearchSubmit(runner)
            return true
        }

        val focused = runner.focusBestInputField("search", "restaurant", "dish", "food")
        if (focused) {
            delay(250)
            if (runner.typeReliably(query)) {
                finishSearchSubmit(runner)
                return true
            }
        }

        val typedInBestField = runner.typeInBestField(query, "search", "restaurant", "dish", "food")
        if (typedInBestField) {
            finishSearchSubmit(runner)
            return true
        }

        return false
    }

    private suspend fun finishSearchSubmit(runner: SandboxedRunner) {
        delay(150)
        runner.pressEnter()
        runner.waitForScreenChange(timeoutMs = 2500)
        delay(600)
    }

    private fun tryTypeIntoSearchField(
        runner: SandboxedRunner,
        query: String,
        allowGenericFallback: Boolean,
    ): Boolean {
        val hints = buildList {
            add("Search for restaurant")
            add("Search for restaurants")
            add("Search for dish")
            add("Search for food")
            add("Search for restaurant, cuisine or a dish")
            add("Search restaurants")
            add("Search dishes")
            add("Search Zomato")
            if (allowGenericFallback) {
                add("Search")
                add("search")
                add("Find")
            }
        }

        return hints.any { hint -> runner.typeInFieldWithHint(hint, query) }
    }

    private fun findSearchEntryPoint(
        elements: List<ScreenElement>,
        allowGenericFallback: Boolean,
    ): ScreenElement? {
        return elements
            .asSequence()
            .filter { it.isClickable }
            .filter { el ->
                val text = listOf(el.text, el.hint, el.contentDescription, el.viewId)
                    .joinToString(" ")
                    .lowercase()
                val looksLikeSearch =
                    text.contains("restaurant") ||
                        text.contains("dish") ||
                        text.contains("food") ||
                        (allowGenericFallback && (text.contains("search") || text.contains("find")))

                looksLikeSearch &&
                    !text.contains("voice") &&
                    !text.contains("mic") &&
                    !text.contains("camera")
            }
            .sortedWith(
                compareByDescending<ScreenElement> { it.isEditable }
                    .thenByDescending { it.width }
                    .thenBy { it.centerY },
            )
            .firstOrNull()
    }

    // Dismiss Zomato-specific modal dialogs that runner.dismissPopups() may miss.
    // Zomato shows these on app start: location permission, notification nudge,
    // update dialog, address confirmation sheet, "New on Zomato" interstitials.
    private suspend fun dismissZomatoSpecificPopups(runner: SandboxedRunner) {
        val screen = runner.readScreen().lowercase()
        val popupSignals = listOf(
            "turn on notifications", "enable notifications", "allow notifications",
            "update zomato", "update app", "new version available",
            "rate us", "rate the app",
            "what's new", "new on zomato",
            "confirm your address", "is your address correct",
        )
        val hasBotherPopup = popupSignals.any { screen.contains(it) }
        if (!hasBotherPopup) return

        // Try the safe dismiss buttons in order
        val dismissWords = listOf(
            "Later", "Not now", "Maybe later", "Skip", "Dismiss",
            "Close", "Cancel", "Remind me later", "No thanks",
        )
        for (word in dismissWords) {
            if (runner.tapByText(word)) {
                delay(400)
                return
            }
        }
    }

    // Shared UI knowledge injected into every Zomato goal so the AI doesn't waste
    // steps discovering what popups look like or where the search bar is.
    private val zomatoUiContext = """
Zomato UI knowledge:
- Main search bar is near the top, labelled "Search for restaurant, cuisine or a dish".
- If a bottom sheet appears with "Turn on notifications" or "Update", tap "Later" or "Not now".
- If an address confirmation sheet appears ("Is your address correct?"), tap "Yes" or "Confirm".
- If a location permission dialog appears, dismiss it with "Not now" — do not grant.
- Delivery time shows as "X mins" next to each restaurant card.
- Restaurant rating shows as a green/yellow badge (e.g. "4.2").
- To add an item tap the green "ADD" button; if a customisation sheet appears, pick the first option and tap "Add Item".
- The cart icon is at the bottom-right; tap it to open View Cart.
- Do NOT tap the microphone icon — use text search only.
    """.trimIndent()

    private fun looksLikeSelectionFollowUp(query: String): Boolean {
        val lower = query.trim().lowercase()
        if (lower.isBlank()) return false
        if (Regex("""^[1-5]$""").matches(lower)) return true
        if (Regex("""\b(first|second|third|fourth|fifth|1st|2nd|3rd|4th|5th)\b""").containsMatchIn(lower)) return true
        if (Regex("""\b(option|item|number|no\.?)\s*[1-5]\b""").containsMatchIn(lower)) return true

        val phrases = listOf(
            "that one",
            "this one",
            "same one",
            "cheaper one",
            "cheapest one",
            "go with",
            "go ahead",
            "place order",
            "checkout",
            "continue",
            "proceed",
        )
        return phrases.any { lower.contains(it) }
    }
}
