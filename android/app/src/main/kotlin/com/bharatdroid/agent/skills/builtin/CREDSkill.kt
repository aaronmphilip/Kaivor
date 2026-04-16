package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class CREDSkill : Skill {

    override val manifest = SkillManifest(
        id = "cred",
        name = "CRED Credit Card Payments",
        version = "5.0.0",
        description = "Pay credit card bills, check rewards, use CRED coins, track dues",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK, Permission.PAYMENT,
        ),
        allowedPackages = setOf("com.dreamplug.androidapp"),
        exampleParamsHint = """{"action": "pay", "card": "HDFC", "amount": "5000"}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase() ?: "check"
        val card = params["card"] as? String ?: ""
        val amount = (params["amount"] as? String) ?: (params["amount"] as? Number)?.toLong()?.toString() ?: ""

        runner.openApp("com.dreamplug.androidapp")
        runner.waitForApp("com.dreamplug.androidapp", timeoutMs = 6000)
        delay(800)
        runner.dismissPopups(2)
        delay(200)

        val goal = when (action) {
            "pay", "payment" ->
                """You are in CRED app. Pay credit card bill${if (card.isNotBlank()) " for $card card" else ""}${if (amount.isNotBlank()) " of Rs $amount" else ""}.
                STEPS: 1) You will see credit card(s) listed on home screen. ${if (card.isNotBlank()) "2) Find and tap the $card card." else "2) Tap the card shown."} 3) Tap 'Pay Bill' or 'Pay Now'. 4) ${if (amount.isNotBlank()) "Enter amount Rs $amount." else "Use the total due amount."} 5) Tap 'Proceed to Pay'. IMPORTANT: Stop before OTP/PIN entry."""

            "rewards", "coins" ->
                "You are in CRED app. Check CRED coins and available rewards. Tap the 'Rewards' or 'Store' or 'Coins' section and read what's shown."

            "bills", "dues", "check" ->
                "You are in CRED app. Show all linked credit cards and their due amounts. Read the card names and outstanding dues."

            "history", "transactions" ->
                """You are in CRED app. Show payment history${if (card.isNotBlank()) " for $card card" else ""}.
                STEPS: 1) ${if (card.isNotBlank()) "Tap $card card." else "Tap the card."} 2) Look for 'History' or 'Transactions' tab. 3) Read recent payments."""

            else ->
                params["goal"] as? String ?: "Do this in CRED: $action $card $amount".trim()
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 20)
        return SkillResult.Success(result)
    }
}
