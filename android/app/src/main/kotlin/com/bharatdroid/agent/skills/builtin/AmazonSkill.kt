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
        allowedPackages = setOf("in.amazon.mShop.android.shopping"),
        exampleParamsHint = """{"action":"search","query":"noise cancelling headphones","maxPrice":2000}""",
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

        // For search tasks: directly type into search field BEFORE delegating to AI
        // This skips the mic/camera/voice confusion entirely
        if (action == "search" && query.isNotBlank()) {
            // Try to find and type directly into the Amazon search bar
            val directTyped = runner.typeInFieldWithHint("Search Amazon", query)
                || runner.typeInFieldWithHint("Search", query)
                || runner.typeInFieldWithHint("search", query)
            if (directTyped) {
                delay(200)
                runner.pressEnter()
                delay(1500) // wait for results
            } else {
                // Tap the search bar area at top, avoiding mic/camera icons on the right
                val (w, h) = runner.getScreenSize()
                runner.tapAtPoint(w * 0.35f, h * 0.07f) // left-center of search bar, fraction not pixels
                delay(400)
                runner.typeReliably(query)
                delay(200)
                runner.pressEnter()
                delay(1500)
            }
        }

        val goal = when (action) {
            "orders", "track" -> "In Amazon, open the Orders section and show recent orders."
            "cart" -> "In Amazon, open the shopping cart and show what is inside."
            "goal" -> params["goal"] as? String ?: query
            "search" -> buildString {
                append("TASK: Find the BEST \"$query\" on Amazon.\n\n")
                append("The search has already been submitted. You should now see results.\n\n")
                append("STEPS:\n")
                append("1. Scroll through the search results to see options\n")
                if (maxPrice != null) append("2. Look for products under Rs $maxPrice\n")
                append("3. Check ratings (4+ stars preferred) and review count\n")
                append("4. Tap on the best matching product to see its details\n")
                append("5. On the product page: scroll down to read reviews, features, specifications\n")
                append("6. Report what you found — product name, price, rating, key features\n\n")
                append("DO NOT press back repeatedly. Stay on the results/product page.\n")
                append("DO NOT go to home screen or open other apps.")
            }
            else -> buildString {
                append("TASK: \"$query\" on Amazon.\n\n")
                append("If search is needed: find the WIDE TEXT INPUT field at the top.\n")
                append("NEVER tap mic/camera/voice/lens icons. Only the text field.\n")
                append("After searching: scroll results, find best match, tap to view details.\n")
                if (maxPrice != null) append("Price limit: Rs $maxPrice\n")
            }
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 22)
        return SkillResult.Success(result)
    }
}
