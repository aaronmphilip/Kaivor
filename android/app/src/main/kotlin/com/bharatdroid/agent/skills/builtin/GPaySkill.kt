package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class GPaySkill : Skill {

    override val manifest = SkillManifest(
        id = "gpay",
        name = "Google Pay UPI",
        version = "5.0.0",
        description = "Send money, pay bills, scan QR codes via Google Pay",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK, Permission.PAYMENT,
        ),
        allowedPackages = setOf("com.google.android.apps.nbu.paisa.user"),
        exampleParamsHint = """{"action": "send", "contact": "Priya", "amount": "200", "note": "lunch"}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase() ?: "home"
        val contact = params["contact"] as? String ?: params["upiId"] as? String ?: ""
        val amount = (params["amount"] as? String) ?: (params["amount"] as? Number)?.toLong()?.toString() ?: ""
        val note = params["note"] as? String ?: ""

        runner.openApp("com.google.android.apps.nbu.paisa.user")
        runner.waitForApp("com.google.android.apps.nbu.paisa.user", timeoutMs = 6000)
        delay(800)
        runner.dismissPopups(2)
        delay(200)

        val goal = when (action) {
            "send", "pay" ->
                """You are in Google Pay. Send Rs $amount to "$contact"${if (note.isNotBlank()) " with note '$note'" else ""}.
                STEPS: 1) Tap 'New Payment' or search people icon. 2) Search for "$contact". 3) Tap the correct contact. 4) Enter amount Rs $amount. 5) ${if (note.isNotBlank()) "Add note '$note'. 6)" else ""} Tap 'Pay'. IMPORTANT: Stop before UPI PIN entry — user will enter PIN manually."""

            "history", "transactions" ->
                "You are in Google Pay. Show recent transaction history. Tap your profile or history section and read the last 5 transactions."

            "balance" ->
                "You are in Google Pay. Check linked bank account balance. Find and tap 'Check Balance' option."

            "scan", "qr" ->
                "You are in Google Pay. Open QR code scanner. Tap the scan/camera icon to scan a QR code."

            "request" ->
                """You are in Google Pay. Request Rs $amount from "$contact".
                STEPS: 1) Tap 'Request' option. 2) Search for "$contact". 3) Tap the contact. 4) Enter amount Rs $amount. 5) Tap 'Request'."""

            else ->
                params["goal"] as? String ?: "Do this in Google Pay: $action $contact $amount".trim()
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 20)
        return SkillResult.Success(result)
    }
}
