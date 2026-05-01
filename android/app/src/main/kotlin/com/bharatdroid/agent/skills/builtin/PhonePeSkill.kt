package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class PhonePeSkill : Skill {

    override val manifest = SkillManifest(
        id = "phonepe",
        name = "PhonePe",
        version = "5.0.0",
        description = "Send money via UPI, pay bills, mobile recharge, check balance, view history on PhonePe",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK, Permission.PAYMENT,
        ),
        allowedPackages = setOf("com.phonepe.app"),
        exampleParamsHint = """{"action": "recharge", "mobile": "9876543210", "amount": 239}""",
        uiKnowledge = """
PhonePe UI guide:
- Home screen: UPI-linked bank balance shown at the top; large circular icon grid below — Send Money, Mobile Recharge, Electricity, DTH, Credit Card, etc.
- Send Money flow: tap "Send Money" → enter phone number or UPI ID in the search/recipient field → contacts list appears below search bar → tap contact → enter amount on keypad → "Proceed to Pay" green button
- Amount entry screen: large rupee (₹) keypad; quick-select amount chips (₹100, ₹200, ₹500); "Proceed to Pay" green button at the bottom
- UPI PIN screen: 4–6 digit numeric keypad with individual PIN dot boxes at the top; fingerprint/biometric option shown on compatible devices; STOP here — do not enter PIN
- Mobile Recharge flow: tap "Mobile Recharge" → enter mobile number → operator auto-detected → browse plan cards (validity, data, price) → select plan → "Proceed to Pay"
- Transaction History: tap "History" tab (bottom nav or home screen) → list of all past transactions with recipient/sender name, amount, date, success/failure badge
- Balance check: tap bank account card or "Check Balance" → UPI PIN required to reveal balance
""".trimIndent(),
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase() ?: "balance"
        val mobile = params["mobile"] as? String ?: ""
        val amount = params["amount"]?.let {
            when (it) {
                is Long -> it.toInt()
                is Int -> it
                is Double -> it.toInt()
                is String -> it.toIntOrNull()
                else -> null
            }
        }
        val upiId = params["upiId"] as? String ?: params["to"] as? String ?: ""
        val note = params["note"] as? String ?: ""

        runner.openApp("com.phonepe.app")
        runner.waitForApp("com.phonepe.app", timeoutMs = 7000)
        delay(800)
        runner.dismissPopups(3)
        delay(200)

        val goal = when (action) {
            "send", "pay" -> {
                if (upiId.isBlank()) return SkillResult.Failure("Provide UPI ID or mobile number to send money to.")
                if (amount == null) return SkillResult.Failure("Provide the amount to send.")
                val goal = buildString {
                    appendLine("You are in PhonePe. Send ₹$amount to \"$upiId\"${if (note.isNotBlank()) " with note \"$note\"" else ""}.")
                    appendLine()
                    appendLine("STEPS:")
                    appendLine("1. Tap 'Send Money' or 'To Mobile Number' or the UPI transfer option on the home screen.")
                    appendLine("2. Enter \"$upiId\" in the recipient field (UPI ID or mobile number).")
                    appendLine("   → A suggestion list appears below — TAP the best matching contact. Never skip this step.")
                    appendLine("3. Tap 'Verify' or 'Proceed'.")
                    appendLine("4. Enter the amount ₹$amount using the keypad.")
                    if (note.isNotBlank()) appendLine("5. Tap the note/remark field and type \"$note\".")
                    val payStep = if (note.isNotBlank()) 6 else 5
                    appendLine("$payStep. Tap 'Proceed' or 'Pay'.")
                    appendLine("${payStep + 1}. STOP before the UPI PIN entry screen — do NOT enter the PIN.")
                    appendLine("${payStep + 2}. Report: recipient name as shown on screen, amount ₹$amount, PIN screen is ready.")
                    appendLine()
                    append("⚠️ NEVER enter the UPI PIN.")
                }.trimEnd()
                return SkillResult.NeedsConfirmation(
                    prompt = "💸 *Send via PhonePe*\n\nAmount: *₹$amount*\nTo: *${upiId.ifBlank { mobile }}*${if (note.isNotBlank()) "\nNote: _${note}_" else ""}\n\n⚠️ You will need to enter your UPI PIN on the phone.\n\nReply *YES* to proceed.",
                    onConfirm = {
                        val result = agent.executeGoal(runner, goal, maxSteps = 55)
                        SkillResult.Success(result)
                    }
                )
            }
            "recharge" -> {
                if (mobile.isBlank()) return SkillResult.Failure("Provide the mobile number to recharge.")
                if (amount == null) return SkillResult.Failure("Provide the recharge amount.")
                val goal = buildString {
                    appendLine("You are in PhonePe. Do a mobile recharge of ₹$amount for number \"$mobile\".")
                    appendLine()
                    appendLine("STEPS:")
                    appendLine("1. Tap 'Mobile Recharge' or 'Recharge' on the home screen.")
                    appendLine("2. Enter the mobile number \"$mobile\".")
                    appendLine("3. Tap 'Proceed' — the operator will be auto-detected.")
                    appendLine("4. Browse the available recharge plan cards.")
                    appendLine("5. Select the plan closest to ₹$amount — look for the exact amount or the nearest match.")
                    appendLine("   Read the plan's validity and data details from the card.")
                    appendLine("6. Tap 'Proceed to Pay'.")
                    appendLine("7. STOP before the UPI PIN entry screen — do NOT enter the PIN.")
                    appendLine("8. Report: the plan selected (amount, validity, data), and that PIN screen is ready.")
                    appendLine()
                    append("⚠️ NEVER enter the UPI PIN.")
                }.trimEnd()
                return SkillResult.NeedsConfirmation(
                    prompt = "📱 *Mobile Recharge via PhonePe*\n\nNumber: *$mobile*\nAmount: *₹$amount*\n\n⚠️ You will need to enter your UPI PIN on the phone.\n\nReply *YES* to recharge now.",
                    onConfirm = {
                        val result = agent.executeGoal(runner, goal, maxSteps = 55)
                        SkillResult.Success(result)
                    }
                )
            }
            "balance" -> {
                """You are in PhonePe. Check the UPI wallet balance.
STEPS: 1) Look for 'Check Balance' or a balance card on the home screen. 2) If not visible, tap on the bank account or UPI section. 3) Tap 'Check Balance'. 4) STOP before the UPI PIN screen — do not enter the PIN. Report what balance information is visible without PIN."""
            }
            "history" -> {
                """You are in PhonePe. View recent transaction history.
STEPS: 1) Look for 'History' or 'Transaction History' in the bottom navigation or home screen. 2) Tap it. 3) Wait for the transactions list to load. 4) Read out the last 5–10 transactions — recipient/sender names, amounts, dates, and success/failure status."""
            }
            else ->
                params["goal"] as? String ?: "Do this in PhonePe: $action $mobile $upiId".trim()
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 55)
        return SkillResult.Success(result)
    }
}
