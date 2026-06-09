package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

/**
 * PriceComparatorSkill opens Amazon and Flipkart, searches the same product on both,
 * reads the first result's price from each, and tells the user which is cheaper.
 *
 * Hackathon one-liner: "Which is cheaper, Amazon or Flipkart for iPhone 15?"
 *
 * Example params:
 *   {"query":"iPhone 15 128GB","maxPrice":80000}
 */
class PriceComparatorSkill : Skill {

    override val manifest = SkillManifest(
        id = "price_comparator",
        name = "Price Comparator (Amazon vs Flipkart)",
        version = "1.0.0",
        description = "Compares prices of the same product on Amazon and Flipkart, reports the cheaper one.",
        author = "bharatclaw-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK,
        ),
        allowedPackages = setOf(
            "in.amazon.mShop.android.shopping",
            "com.flipkart.android",
        ),
        exampleParamsHint = """{"query":"iPhone 15 128GB"}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val query = params["query"] as? String
            ?: params["product"] as? String
            ?: params["goal"] as? String
            ?: return SkillResult.Failure("What product should I compare? (provide 'query')")

        // --- Amazon leg ----------------------------------------------------------
        runner.openApp("in.amazon.mShop.android.shopping")
        runner.waitForApp("in.amazon.mShop.android.shopping", timeoutMs = 7000)
        delay(700)
        runner.dismissPopups(2)
        delay(150)

        // Direct type into search bar
        val amazonTyped = runner.typeInFieldWithHint("Search Amazon.in", query)
            || runner.typeInFieldWithHint("Search Amazon", query)
            || runner.typeInFieldWithHint("Search", query)
        if (amazonTyped) {
            delay(200); runner.pressEnter(); delay(2000)
        } else {
            val (w, h) = runner.getScreenSize()
            runner.tapAtPoint(w * 0.35f, h * 0.07f)
            delay(400); runner.typeReliably(query)
            delay(200); runner.pressEnter(); delay(2000)
        }

        val amazonGoal = buildString {
            append("You are in Amazon. Search results for \"$query\" are on screen.\n")
            append("Your ONLY job: READ the price of the FIRST (top) product result.\n\n")
            append("STEPS:\n")
            append("1. Look at the first product card in the list (just below the search bar / filter chips).\n")
            append("2. Find its price. It starts with Rs, INR, or the rupee symbol. Example: 'Rs 74,900' or 'INR 79,999'.\n")
            append("3. Call done with summary EXACTLY like: 'Amazon price: Rs 74,900 for <product-name>'.\n\n")
            append("DO NOT tap the product. DO NOT scroll beyond the first result. Just READ.")
        }
        val amazonResult = agent.executeGoal(runner, amazonGoal, maxSteps = 65)
        val amazonPrice = parsePrice(amazonResult)

        // --- Flipkart leg --------------------------------------------------------
        runner.openApp("com.flipkart.android")
        runner.waitForApp("com.flipkart.android", timeoutMs = 6000)
        delay(700)
        runner.dismissPopups(2)
        delay(150)

        val flipkartTyped = runner.typeInFieldWithHint("Search for Products", query)
            || runner.typeInFieldWithHint("Search", query)
        if (flipkartTyped) {
            delay(200); runner.pressEnter(); delay(1800)
        } else {
            val (w, h) = runner.getScreenSize()
            runner.tapAtPoint(w * 0.35f, h * 0.07f)
            delay(400); runner.typeReliably(query)
            delay(200); runner.pressEnter(); delay(1800)
        }

        val flipkartGoal = buildString {
            append("You are in Flipkart. Search results for \"$query\" are on screen.\n")
            append("Your ONLY job: READ the price of the FIRST (top) product result.\n\n")
            append("STEPS:\n")
            append("1. Look at the first product card in the list.\n")
            append("2. Find its price. It starts with Rs, INR, or the rupee symbol. Example: 'Rs 69,900'.\n")
            append("3. Call done with summary EXACTLY like: 'Flipkart price: Rs 69,900 for <product-name>'.\n\n")
            append("DO NOT tap the product. DO NOT scroll beyond the first result. Just READ.")
        }
        val flipkartResult = agent.executeGoal(runner, flipkartGoal, maxSteps = 65)
        val flipkartPrice = parsePrice(flipkartResult)

        // --- Compare & report ---------------------------------------------------
        val summary = buildString {
            appendLine("*Price Comparison for \"$query\"*")
            appendLine()
            appendLine("*Amazon*: ${amazonPrice?.let { "Rs %,d".format(it) } ?: "could not read"}")
            appendLine("*Flipkart*: ${flipkartPrice?.let { "Rs %,d".format(it) } ?: "could not read"}")
            appendLine()
            when {
                amazonPrice != null && flipkartPrice != null -> {
                    val diff = kotlin.math.abs(amazonPrice - flipkartPrice)
                    val cheaper = if (amazonPrice < flipkartPrice) "Amazon" else "Flipkart"
                    appendLine("*$cheaper is cheaper by Rs %,d*".format(diff))
                }
                amazonPrice != null -> appendLine("Only Amazon price was readable.")
                flipkartPrice != null -> appendLine("Only Flipkart price was readable.")
                else -> appendLine("Could not read either price automatically.")
            }
        }
        return SkillResult.Success(summary)
    }

    /** Extract first rupee/Rs price from a string and return as integer rupees. */
    internal fun parsePrice(text: String): Int? {
        val match = Regex("""(?:\u20B9|Rs\.?|INR)\s*([\d,]+)""", RegexOption.IGNORE_CASE).find(text)
        return match?.groupValues?.getOrNull(1)?.replace(",", "")?.toIntOrNull()
    }
}
