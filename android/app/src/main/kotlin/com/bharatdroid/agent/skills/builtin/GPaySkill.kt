package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class GPaySkill : Skill {

    override val manifest = SkillManifest(
        id = "gpay",
        name = "Google Pay UPI",
        version = "6.0.0",
        description = "Send money, pay bills, request money, scan QR codes via Google Pay",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK, Permission.PAYMENT,
        ),
        allowedPackages = setOf("com.google.android.apps.nbu.paisa.user"),
        exampleParamsHint = """{"action": "send", "contact": "Priya", "amount": "200", "note": "lunch"}""",
        uiKnowledge = """
Google Pay UI guide:
- Home screen: search/pay bar at the top reads "Pay phone number or UPI ID"; "New payment" button below it; recent contacts shown as circular avatars in a row
- Recent contacts: tappable circles with the contact's name; tap to go straight to the payment screen
- New payment flow: tap "New payment" → type phone number or UPI ID → tap matching contact → enter amount → optional note → "Pay" button
- Amount entry: large numpad with rupee display; "Pay" button becomes active once amount is entered
- UPI PIN screen: row of 4–6 PIN dots at the top; numeric keypad below — STOP here, do NOT enter PIN
- Businesses / Pay bills: "Pay bills" section with category icons — Electricity, Gas, Water, Broadband, etc.
- Transaction history: tap your avatar/profile picture top right → recent transactions and linked bank accounts
- QR scanner: camera icon in the search bar or dedicated "Scan QR" option
- Request money: tap "Request" or "Split" option to ask someone to pay you
""".trimIndent(),
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase() ?: "home"
        val contact = params["contact"] as? String ?: params["upiId"] as? String
            ?: params["to"] as? String ?: ""
        val amount = (params["amount"] as? String)
            ?: (params["amount"] as? Number)?.toLong()?.toString() ?: ""
        val note = params["note"] as? String ?: ""

        runner.openApp("com.google.android.apps.nbu.paisa.user")
        runner.waitForApp("com.google.android.apps.nbu.paisa.user", timeoutMs = 7000)
        delay(800)
        runner.dismissPopups(2)
        delay(200)

        // For send/pay: try to type the contact directly into the search/pay bar
        if (contact.isNotBlank() && action in setOf("send", "pay", "request")) {
            val typed = runner.typeInFieldWithHint("Pay phone number or UPI ID", contact)
                || runner.typeInFieldWithHint("Pay", contact)
                || runner.typeInFieldWithHint("Search", contact)
            if (typed) {
                delay(1500) // wait for contact/phonebook suggestions to load
            }
        }

        val goal = when (action) {
            "send", "pay" -> {
                if (contact.isBlank()) return SkillResult.Failure("Who should I send money to? Provide a contact name, phone number, or UPI ID.")
                if (amount.isBlank()) return SkillResult.Failure("How much money should I send?")
                val noteStep = if (note.isNotBlank())
                    "\n5. Tap the 'Add a note' or 'Message' field and type \"$note\"."
                else ""
                val payStep = if (note.isNotBlank()) 6 else 5
                val stopStep = payStep + 1
                val reportStep = stopStep + 1
                val goal = """You are in Google Pay. Send ₹$amount to "$contact"${if (note.isNotBlank()) " with note \"$note\"" else ""}.
STEPS:
1. The contact search field may already have "$contact" typed. If not, tap "New payment" and type "$contact".
2. A suggestion list appears below — TAP the suggestion that best matches "$contact".
   (If no suggestion appears, type the phone number or UPI ID directly.)
3. Verify the recipient name shown on screen matches "$contact".
4. On the amount screen, enter ₹$amount using the number pad.$noteStep
$payStep. Tap the "Pay" button.
$stopStep. STOP at the UPI PIN entry screen — do NOT enter the PIN.
$reportStep. Report: recipient name confirmed as shown on screen, amount ₹$amount, PIN screen is ready.

⚠️ NEVER enter the UPI PIN — this is a sensitive action
⚠️ Always tap a suggestion from the list after typing the contact name. Never skip this step."""
                return SkillResult.NeedsConfirmation(
                    prompt = "💸 *Send via Google Pay*\n\nAmount: *₹$amount*\nTo: *$contact*${if (note.isNotBlank()) "\nNote: _${note}_" else ""}\n\n⚠️ You will need to enter your UPI PIN on the phone.\n\nReply *YES* to proceed.",
                    onConfirm = {
                        val result = agent.executeGoal(runner, goal, maxSteps = 55)
                        SkillResult.Success(result)
                    }
                )
            }

            "request" -> {
                if (contact.isBlank()) return SkillResult.Failure("Who should I request money from?")
                if (amount.isBlank()) return SkillResult.Failure("How much money should I request?")
                """You are in Google Pay. Request ₹$amount from "$contact".
STEPS:
1. Look for a "Request" or "Split" option on the home screen
2. If found, tap it; otherwise tap "New payment" then look for a "Request" tab at the top
3. Type "$contact" in the search field
4. Tap the matching contact
5. Enter ₹$amount
6. ${if (note.isNotBlank()) "Add note \"$note\"\n7. " else ""}Tap "Request"
7. Confirm the request was sent — report back"""
            }

            "history", "transactions" ->
                """You are in Google Pay. Show recent transaction history.
STEPS:
1. Tap your profile picture/avatar at the top right
2. Look for "Transaction history" or scroll to see recent transactions
3. Read the last 5–7 transactions — recipient/sender, amount, date, success/failure"""

            "balance" ->
                """You are in Google Pay. Check linked bank account balance.
STEPS:
1. Tap your profile picture or look for a bank account card on the home screen
2. Tap "Check balance" or the bank account
3. STOP before the UPI PIN screen — do NOT enter PIN
4. Report what information is visible about the account without needing PIN"""

            "scan", "qr" ->
                """You are in Google Pay. Open the QR code scanner.
STEPS:
1. Look for a camera or QR scan icon in the search bar or on the home screen
2. Tap "Scan QR code" or the camera icon
3. The camera viewfinder opens — report that it is ready to scan"""

            "bill", "bills" -> {
                val billType = note.ifBlank { "electricity" }
                """You are in Google Pay. Pay a $billType bill.
STEPS:
1. Scroll down on the home screen to find the "Pay bills" or "Businesses" section
2. Tap the relevant category icon (${billType.replaceFirstChar { it.uppercase() }})
3. Look for the biller or operator
4. STOP before payment entry — report what billers are available and what information is needed (account number, consumer ID, etc.)"""
            }

            else ->
                params["goal"] as? String ?: "Do this in Google Pay: $action $contact $amount".trim()
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 55)
        return SkillResult.Success(result)
    }
}
