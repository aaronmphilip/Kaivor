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
            "search" -> buildString {
                append("TASK: Find the BEST \"$query\" on Amazon.\n\n")
                append("⚠️ SEARCH IS ALREADY DONE. Product results are NOW on screen.\n")
                append("⚠️ DO NOT tap the search bar, camera icon, or mic icon at the top.\n")
                append("   The camera (📷) opens photo search. The mic (🎤) opens voice search. NEVER tap them.\n")
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
                append("⚠️ NEVER tap the camera (📷) or mic (🎤) icons next to the search bar.\n")
                append("If search is needed: tap the WIDE TEXT FIELD in the center of the search bar only.\n")
                append("After searching: scroll results, find best match, tap to view details.\n")
                if (maxPrice != null) append("Price limit: Rs $maxPrice\n")
            }
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 22)
        return SkillResult.Success(result)
    }
}
