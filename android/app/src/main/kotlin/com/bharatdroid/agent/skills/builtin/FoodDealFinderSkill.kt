package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

/**
 * FoodDealFinderSkill — compares the same dish on Swiggy AND Zomato, reports cheaper.
 *
 * Hackathon one-liner: "Find the cheapest biryani — Swiggy or Zomato?"
 *
 * Example params:
 *   {"query":"chicken biryani"}
 */
class FoodDealFinderSkill : Skill {

    override val manifest = SkillManifest(
        id = "food_deal_finder",
        name = "Food Deal Finder (Swiggy vs Zomato)",
        version = "1.0.0",
        description = "Compares prices/offers for a dish on Swiggy and Zomato, reports the cheaper option.",
        author = "bharatclaw-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK,
        ),
        allowedPackages = setOf(
            "in.swiggy.android",
            "com.application.zomato",
        ),
        exampleParamsHint = """{"query":"chicken biryani"}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val query = params["query"] as? String
            ?: params["dish"] as? String
            ?: return SkillResult.Failure("What dish should I compare? (provide 'query')")

        // --- Swiggy leg ----------------------------------------------------------
        runner.openApp("in.swiggy.android")
        runner.waitForApp("in.swiggy.android", timeoutMs = 6000)
        delay(700)
        runner.dismissPopups(2)
        delay(150)

        val swiggyTyped = runner.typeInFieldWithHint("Search for restaurants", query)
            || runner.typeInFieldWithHint("Search", query)
        if (swiggyTyped) { delay(200); runner.pressEnter(); delay(1800) }

        val swiggyGoal = buildString {
            append("You are in Swiggy. Find the cheapest \"$query\" visible.\n\n")
            append("STEPS:\n")
            append("1. If no search results yet, tap the search bar, type \"$query\", submit.\n")
            append("2. Scroll through the results — look at restaurant cards AND dish cards.\n")
            append("3. Find the LOWEST price for \"$query\" shown on any card. Prices start with ?.\n")
            append("4. Note the restaurant name and the rating (stars out of 5).\n")
            append("5. Call done with summary EXACTLY like: 'Swiggy: ?180 at <Restaurant>, rating 4.3'.\n\n")
            append("DO NOT add to cart. DO NOT order. Just READ and report.")
        }
        val swiggyResult = agent.executeGoal(runner, swiggyGoal, maxSteps = 40)
        val swiggyPrice = parsePrice(swiggyResult)

        // --- Zomato leg ----------------------------------------------------------
        runner.openApp("com.application.zomato")
        runner.waitForApp("com.application.zomato", timeoutMs = 6000)
        delay(700)
        runner.dismissPopups(2)
        delay(150)

        val zomatoTyped = runner.typeInFieldWithHint("Search for restaurant", query)
            || runner.typeInFieldWithHint("Search", query)
        if (zomatoTyped) { delay(200); runner.pressEnter(); delay(1800) }

        val zomatoGoal = buildString {
            append("You are in Zomato. Find the cheapest \"$query\" visible.\n\n")
            append("STEPS:\n")
            append("1. If no results yet, tap search, type \"$query\", submit.\n")
            append("2. Scroll through results — restaurant cards AND dish cards.\n")
            append("3. Find the LOWEST price for \"$query\". Prices start with ?.\n")
            append("4. Note the restaurant name and rating.\n")
            append("5. Call done with summary EXACTLY like: 'Zomato: ?210 at <Restaurant>, rating 4.1'.\n\n")
            append("DO NOT add to cart. DO NOT order. Just READ and report.")
        }
        val zomatoResult = agent.executeGoal(runner, zomatoGoal, maxSteps = 40)
        val zomatoPrice = parsePrice(zomatoResult)

        // --- Compare ------------------------------------------------------------
        val summary = buildString {
            appendLine("??? *Food Deal: \"$query\"*")
            appendLine()
            appendLine("• *Swiggy*: $swiggyResult")
            appendLine("• *Zomato*: $zomatoResult")
            appendLine()
            when {
                swiggyPrice != null && zomatoPrice != null -> {
                    val diff = kotlin.math.abs(swiggyPrice - zomatoPrice)
                    val cheaper = if (swiggyPrice < zomatoPrice) "Swiggy" else "Zomato"
                    appendLine("? *$cheaper is cheaper by ?%,d*".format(diff))
                }
                swiggyPrice != null -> appendLine("?? Only Swiggy price was readable.")
                zomatoPrice != null -> appendLine("?? Only Zomato price was readable.")
                else -> appendLine("?? Could not parse prices — see raw results above.")
            }
        }
        return SkillResult.Success(summary)
    }

    private fun parsePrice(text: String): Int? {
        val match = Regex("""(?:?|Rs\.?|INR)\s*([\d,]+)""", RegexOption.IGNORE_CASE).find(text)
        return match?.groupValues?.getOrNull(1)?.replace(",", "")?.toIntOrNull()
    }
}
