package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class CREDSkill : Skill {

    override val manifest = SkillManifest(
        id = "cred",
        name = "CRED Credit Card Payments",
        version = "6.0.0",
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
        uiKnowledge = """
CRED app UI guide:
- Home screen: dark/black background; linked credit cards shown as swipeable cards. Each card displays bank name (HDFC, SBI, ICICI, etc.), last 4 digits, outstanding due amount, and due date.
- Card selection: swipe left/right to switch between linked cards; tap a card to open its detail screen.
- Card detail screen: shows total outstanding, minimum due, last statement amount, due date, and a prominent "Pay Bill" or "Pay Now" button.
- Pay Bill flow: tap "Pay Bill" → amount entry screen shows total due pre-filled; user can change amount → tap "Proceed to Pay" → UPI/NEFT selection screen → STOP before OTP or UPI PIN entry.
- Amount input: numeric keypad; Rs symbol shown. Pre-fills with total outstanding — can be edited.
- OTP/UPI PIN screen: digit boxes appear; STOP here and do NOT enter — user handles it manually.
- CRED Coins / Rewards: tap the "Store" or "Rewards" icon (gift/coin icon) in the bottom nav or home screen. Shows available coin balance and categories (vouchers, cashback, deals).
- Transaction history: inside the card detail screen; tap "Transactions" or "History" tab; chronological list of payments with date, amount, status (Success/Failed).
- Bottom navigation: Home (cards overview) | Store (rewards) | Pay (scan & pay UPI) | Profile.
- Popups to dismiss: "Rate CRED", "Enable notifications", onboarding tips — tap "Later", "Skip", or the × button.
- CRITICAL: Never enter OTP, UPI PIN, or card CVV — always stop before that screen and report that payment is ready for user action.
""".trimIndent(),
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
            "pay", "payment" -> {
                if (amount.isBlank() && card.isBlank()) return SkillResult.Failure("Which card and how much should I pay?")
                val payGoal = buildString {
                    appendLine("You are in CRED. Pay the credit card bill${if (card.isNotBlank()) " for the $card card" else ""}${if (amount.isNotBlank()) " of ₹$amount" else " (use total due)"}.")
                    appendLine()
                    appendLine("STEPS:")
                    appendLine("1. On the home screen, swipe through the cards${if (card.isNotBlank()) " and select the $card card" else " and tap the visible card"}.")
                    appendLine("2. Tap 'Pay Bill' or 'Pay Now' on the card detail screen.")
                    if (amount.isNotBlank()) {
                        appendLine("3. The amount field may show the total due — clear it and enter ₹$amount.")
                    } else {
                        appendLine("3. Leave the amount as the total outstanding due.")
                    }
                    appendLine("4. Tap 'Proceed to Pay'.")
                    appendLine("5. STOP at the UPI PIN / OTP screen — do NOT enter it.")
                    appendLine("6. Report: card name, amount ₹${amount.ifBlank { "total due" }}, and that the PIN screen is ready for the user.")
                    appendLine()
                    appendLine("STRICT RULES:")
                    appendLine("- Do NOT enter UPI PIN, OTP, or CVV — user does that manually.")
                    appendLine("- Do NOT tap Schedule or EMI options unless asked.")
                }
                return SkillResult.NeedsConfirmation(
                    prompt = "💳 *CRED Bill Payment*\n\nCard: *${card.ifBlank { "linked card" }}*\nAmount: *₹${amount.ifBlank { "total due" }}*\n\n⚠️ You will need to enter your UPI PIN on the phone.\n\nReply *YES* to proceed.",
                    onConfirm = {
                        val result = agent.executeGoal(runner, payGoal, maxSteps = 55)
                        SkillResult.Success(result)
                    }
                )
            }

            "rewards", "coins" -> buildString {
                appendLine("You are in CRED. Check CRED coins balance and available rewards.")
                appendLine()
                appendLine("STEPS:")
                appendLine("1. Tap the 'Store' or 'Rewards' icon in the bottom navigation bar.")
                appendLine("2. Note the coin balance shown at the top.")
                appendLine("3. Browse the reward categories (vouchers, cashback, deals).")
                appendLine("4. Report: coin balance and 3–5 notable rewards available.")
            }

            "bills", "dues", "check" -> buildString {
                appendLine("You are in CRED. Show all linked credit cards and their outstanding dues.")
                appendLine()
                appendLine("STEPS:")
                appendLine("1. On the home screen, read all visible card tiles.")
                appendLine("2. For each card, note: bank name, last 4 digits, outstanding amount, due date.")
                appendLine("3. Swipe left to see additional cards if more than one is linked.")
                appendLine("4. Report a summary of all cards and dues.")
            }

            "history", "transactions" -> buildString {
                appendLine("You are in CRED. Show recent payment history${if (card.isNotBlank()) " for the $card card" else ""}.")
                appendLine()
                appendLine("STEPS:")
                appendLine("1. ${if (card.isNotBlank()) "Find and tap the $card card on the home screen." else "Tap the card on the home screen."}")
                appendLine("2. Look for a 'Transactions' or 'History' tab on the card detail screen.")
                appendLine("3. Read the last 7–10 entries: date, amount, payment status (Success/Failed).")
                appendLine("4. Report the recent payment history.")
            }

            else ->
                params["goal"] as? String ?: "Do this in CRED: $action $card $amount".trim()
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 55)
        return SkillResult.Success(result)
    }
}
