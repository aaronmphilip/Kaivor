package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class ZeptoSkill : Skill {

    override val manifest = SkillManifest(
        id = "zepto",
        name = "Zepto Grocery Order",
        version = "1.0.0",
        description = "Order groceries from Zepto (10-minute delivery)",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP,
            Permission.READ_SCREEN,
            Permission.TAP,
            Permission.TYPE,
            Permission.SCROLL,
            Permission.PAYMENT,
        ),
        allowedPackages = setOf("com.zepto.app"),
        exampleParamsHint = """{"item": "milk 1 litre", "quantity": 2}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val item = params["item"] as? String
            ?: return SkillResult.Failure("What grocery item do you need?")
        val quantity = (params["quantity"] as? Long)?.toInt() ?: 1

        runner.openApp("com.zepto.app")
        if (!runner.waitForApp("com.zepto.app", timeoutMs = 6000)) {
            return SkillResult.Failure("Zepto didn't open. Is it installed?")
        }
        delay(2000)

        // Search for item
        val searched = runner.tapByText("Search for products")
            || runner.tapByText("Search")
        if (!searched) return SkillResult.Failure("Could not find Zepto search. App may have updated.")
        delay(600)

        runner.typeInFieldWithHint("Search", item)
        delay(1500)

        val screen = runner.readScreen()

        return SkillResult.Success(
            "Searched Zepto for *$item* (qty: $quantity).\n\n" +
            "```\n${screen.take(500)}\n```\n\n" +
            "Reply with the exact product name to add to cart."
        )
    }
}
