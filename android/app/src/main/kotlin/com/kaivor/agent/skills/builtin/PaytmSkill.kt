package com.kaivor.agent.skills.builtin

import com.kaivor.agent.skills.*
import kotlinx.coroutines.delay

class PaytmSkill : Skill {

    override val manifest = SkillManifest(
        id = "paytm",
        name = "Paytm Payments",
        version = "6.0.0",
        description = "Send money, mobile recharge, pay bills, check wallet balance, view history via Paytm",
        author = "kaivor-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK, Permission.PAYMENT,
        ),
        allowedPackages = setOf("net.one97.paytm"),
        exampleParamsHint = """{"action": "recharge", "mobile": "9876543210", "amount": "199"}""",
        uiKnowledge = """
Paytm UI guide:
- Home screen: blue Paytm header at the top; search bar reads "Search for services, products..."; quick-action tiles below - Mobile Recharge, Electricity, DTH, Send Money, Bank Transfer, etc.
- Send Money flow: tap "Send Money" or "Pay" ? enter contact name or mobile number in the search field ? tap the matching contact ? enter amount on the numeric keypad ? tap "Pay" or "Proceed"
- Amount entry: large rupee (?) input; quick-amount chips (?100, ?200, ?500, ?1000); "Pay" button at the bottom
- UPI PIN screen: 4-6 digit PIN entry with individual dot boxes; fingerprint option on compatible devices - STOP here, do NOT enter PIN
- Mobile Recharge: tap "Mobile Recharge" ? enter mobile number ? operator auto-detected ? browse plans (validity, data, calls, price) ? select plan ? "Proceed to Pay"
- Paytm Wallet: shows wallet balance prominently; can be used to pay directly without UPI PIN for small amounts
- Bill Payments: "Recharge & Pay Bills" section ? Electricity, Gas, Water, Broadband, FASTag, etc. - select category, enter consumer/account number, fetch bill, then pay
- Transaction History / Passbook: tap "Passbook" or "History" from the home screen or profile ? chronological list of all transactions with amount, recipient/sender, date, status (Success/Failed/Pending)
- Offers: "All Services" tab at the bottom shows cashback offers and deals
""".trimIndent(),
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase() ?: "home"
        val mobile = params["mobile"] as? String ?: params["number"] as? String ?: ""
        val contact = params["contact"] as? String ?: ""
        val amount = (params["amount"] as? String) ?: (params["amount"] as? Number)?.toLong()?.toString() ?: ""
        val billType = params["billType"] as? String ?: params["type"] as? String ?: "electricity"

        runner.openApp("net.one97.paytm")
        runner.waitForApp("net.one97.paytm", timeoutMs = 6000)
        delay(800)
        runner.dismissPopups(2)
        delay(200)

        val goal = when (action) {
            "recharge", "mobile" -> {
                val number = mobile.ifBlank { contact }
                if (number.isBlank()) return SkillResult.Failure("Which mobile number should I recharge?")
                if (amount.isBlank()) return SkillResult.Failure("What amount should I recharge?")
                val goal = buildString {
                    appendLine("You are in Paytm. Do a mobile recharge of Rs $amount for number \"$number\".")
                    appendLine()
                    appendLine("STEPS:")
                    appendLine("1. Tap 'Mobile Recharge' from the home screen tiles.")
                    appendLine("2. Enter the mobile number \"$number\" in the number field.")
                    appendLine("3. The operator should auto-detect. If not, select the correct operator.")
                    appendLine("4. Browse the available recharge plans.")
                    appendLine("5. Select the plan closest to Rs $amount - look for the exact amount or nearest match.")
                    appendLine("6. Tap 'Proceed to Pay'.")
                    appendLine("7. STOP before the UPI PIN entry screen - do NOT enter the PIN.")
                    appendLine("8. Report: plan selected, validity, data, and that PIN screen is ready.")
                    appendLine()
                    appendLine("STRICT RULES:")
                    appendLine("- Do NOT enter UPI PIN or OTP - user handles that manually.")
                    appendLine("- If the operator is not detected, tap 'Change Operator' and select manually.")
                }
                return SkillResult.NeedsConfirmation(
                    prompt = "*Mobile Recharge via Paytm*\n\nNumber: *$number*\nAmount: *Rs $amount*\n\nYou will need to enter your UPI PIN on the phone.\n\nReply *YES* to proceed.",
                    onConfirm = {
                        val result = agent.executeGoal(runner, goal, maxSteps = 60)
                        SkillResult.Success(result)
                    }
                )
            }

            "send", "pay" -> {
                val recipient = contact.ifBlank { mobile }
                if (recipient.isBlank()) return SkillResult.Failure("Who should I send money to? Provide a contact name or mobile number.")
                if (amount.isBlank()) return SkillResult.Failure("How much should I send?")
                val goal = buildString {
                    appendLine("You are in Paytm. Send Rs $amount to \"$recipient\".")
                    appendLine()
                    appendLine("STEPS:")
                    appendLine("1. Tap 'Send Money' or 'Pay' on the home screen.")
                    appendLine("2. Type \"$recipient\" in the search/contact field.")
                    appendLine("3. Tap the most relevant contact or number from the list.")
                    appendLine("4. Verify the recipient name on screen matches \"$recipient\".")
                    appendLine("5. Enter Rs $amount using the number pad.")
                    appendLine("6. Tap 'Pay' or 'Proceed'.")
                    appendLine("7. STOP at the UPI PIN entry screen - do NOT enter the PIN.")
                    appendLine("8. Report: recipient confirmed, amount Rs $amount, PIN screen waiting for user.")
                    appendLine()
                    appendLine("STRICT RULES:")
                    appendLine("- Do NOT enter UPI PIN - user enters it manually.")
                    appendLine("- If the contact is not found, ask the user for the exact UPI ID or phone number.")
                }
                return SkillResult.NeedsConfirmation(
                    prompt = "*Send Money via Paytm*\n\nAmount: *Rs $amount*\nTo: *$recipient*\n\nYou will need to enter your UPI PIN on the phone.\n\nReply *YES* to proceed.",
                    onConfirm = {
                        val result = agent.executeGoal(runner, goal, maxSteps = 55)
                        SkillResult.Success(result)
                    }
                )
            }

            "balance" ->
                buildString {
                    appendLine("You are in Paytm. Check wallet balance.")
                    appendLine("STEPS:")
                    appendLine("1. Look for the 'Paytm Wallet' card on the home screen - balance shown there.")
                    appendLine("2. If not visible, tap on 'Wallet' or 'Balance' from the home screen.")
                    appendLine("3. Report the wallet balance shown without entering any PIN.")
                    appendLine("4. For bank account balance, STOP before the UPI PIN screen.")
                }

            "bills", "bill" -> buildString {
                appendLine("You are in Paytm. Pay a $billType bill.")
                appendLine("STEPS:")
                appendLine("1. Tap 'Recharge & Pay Bills' on the home screen.")
                appendLine("2. Find and tap the '${billType.replaceFirstChar { it.uppercase() }}' category.")
                appendLine("3. Enter the consumer number / account number / operator details as needed.")
                appendLine("4. Tap 'Fetch Bill' or 'Get Bill' to load the outstanding amount.")
                appendLine("5. STOP before payment - report the biller name and outstanding amount due.")
                appendLine("6. Do NOT tap 'Proceed to Pay' without user confirmation.")
            }

            "history", "passbook" ->
                buildString {
                    appendLine("You are in Paytm. Show recent transaction history.")
                    appendLine("STEPS:")
                    appendLine("1. Tap 'Passbook' or 'History' on the home screen or bottom nav.")
                    appendLine("2. Wait for the transaction list to load.")
                    appendLine("3. Read the last 7-10 transactions - recipient/sender, amount, date, status.")
                }

            else ->
                params["goal"] as? String ?: "Do this in Paytm: $action $contact $mobile $amount".trim()
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 60)
        return SkillResult.Success(result)
    }
}
