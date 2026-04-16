package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class PaytmSkill : Skill {

    override val manifest = SkillManifest(
        id = "paytm",
        name = "Paytm Payments",
        version = "5.0.0",
        description = "Send money, recharge, pay bills, check wallet balance via Paytm",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK, Permission.PAYMENT,
        ),
        allowedPackages = setOf("net.one97.paytm"),
        exampleParamsHint = """{"action": "recharge", "mobile": "9876543210", "amount": "199"}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase() ?: "home"
        val mobile = params["mobile"] as? String ?: params["number"] as? String ?: ""
        val contact = params["contact"] as? String ?: ""
        val amount = (params["amount"] as? String) ?: (params["amount"] as? Number)?.toLong()?.toString() ?: ""

        runner.openApp("net.one97.paytm")
        runner.waitForApp("net.one97.paytm", timeoutMs = 6000)
        delay(800)
        runner.dismissPopups(2)
        delay(200)

        val goal = when (action) {
            "recharge", "mobile" ->
                """You are in Paytm. Recharge mobile number "${mobile.ifBlank { contact }}" for Rs $amount.
                STEPS: 1) Tap 'Mobile Recharge' or 'Recharge & Pay Bills'. 2) Enter number "${mobile.ifBlank { contact }}". 3) Select operator if asked. 4) Browse plans and pick one for Rs $amount or nearest value. 5) Tap 'Proceed to Pay'. Stop before PIN/OTP."""

            "send", "pay" ->
                """You are in Paytm. Send Rs $amount to "$contact".
                STEPS: 1) Tap 'Send Money' or 'Pay'. 2) Search for "$contact". 3) Tap the correct result. 4) Enter Rs $amount. 5) Tap 'Proceed'. IMPORTANT: Stop before entering UPI PIN."""

            "balance" ->
                "You are in Paytm. Check wallet balance. Tap on 'Paytm Wallet' or 'Balance' section and read the balance shown."

            "bills" ->
                """You are in Paytm. Pay a utility bill.
                STEPS: 1) Tap 'Recharge & Pay Bills'. 2) Choose the bill type (electricity, water, etc). 3) Fill in details. 4) Tap 'Proceed'."""

            "history" ->
                "You are in Paytm. Show recent transaction history. Tap 'Passbook' or 'History' and read recent entries."

            else ->
                params["goal"] as? String ?: "Do this in Paytm: $action $contact $mobile $amount".trim()
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 20)
        return SkillResult.Success(result)
    }
}
