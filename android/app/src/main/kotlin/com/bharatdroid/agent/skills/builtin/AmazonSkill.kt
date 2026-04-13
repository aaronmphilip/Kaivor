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
        val maxPrice = (params["maxPrice"] as? Long)?.toInt()
        val filter = params["filter"] as? String ?: ""

        runner.openApp("in.amazon.mShop.android.shopping")
        runner.waitForApp("in.amazon.mShop.android.shopping", timeoutMs = 7000)
        delay(350)
        runner.dismissPopups(2)
        delay(150)

        val goal = when (action) {
            "orders", "track" -> "In Amazon, open the Orders section and show recent orders."
            "cart" -> "In Amazon, open the shopping cart and show what is inside."
            "goal" -> params["goal"] as? String ?: query
            else -> buildString {
                append("TASK: Search for and show \"$query\" on Amazon.\n\n")
                append("CRITICAL RULES:\n")
                append("1. Find the search INPUT FIELD at the top (must be editable text box, NOT a microphone or icon)\n")
                append("2. Tap only the TEXT input field. NEVER click microphone icons or voice buttons\n")
                append("3. If you see \"Speak now\", \"Listening\", or voice UI, press back immediately\n")
                append("4. Type \"$query\" into the search field\n")
                append("5. Press Enter to submit the search\n")
                append("6. Wait for results to load\n")
                append("7. Scroll down to see multiple product options\n")
                append("8. Find the BEST matching product")
                if (maxPrice != null) append(" under Rs $maxPrice")
                append("\n")
                append("9. Tap on that product to show its details\n\n")
                append("SUCCESS: Successfully found and opened the product.\n\n")
                if (maxPrice != null) append("Price limit: Rs $maxPrice\n\n")
                append("DO NOT use voice search. Only use the text input field.")
            }
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 22)
        return SkillResult.Success(result)
    }
}
