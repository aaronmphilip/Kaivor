package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.ScreenAgent
import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

/**
 * BillSplitterSkill — splits an amount across N people and drafts UPI requests via PhonePe.
 *
 * Hackathon one-liner: "Split ?1800 for pizza among Aarav, Meera, Rohan."
 *
 * Flow:
 *   1. Compute per-head amount = total / (contacts.size + 1)  (payer included).
 *   2. Open PhonePe.
 *   3. For each contact: navigate to 'Request Money' ? pick contact ? enter share ? draft.
 *   4. STOP before actually sending — user must confirm each request manually for safety.
 *
 * Example params:
 *   {"amount":1800,"contacts":["Aarav","Meera","Rohan"],"note":"Pizza Friday"}
 */
class BillSplitterSkill : Skill {

    override val manifest = SkillManifest(
        id = "bill_splitter",
        name = "Bill Splitter (UPI Request)",
        version = "1.0.0",
        description = "Splits a bill across multiple people and drafts a UPI request to each via PhonePe.",
        author = "bharatclaw-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK, Permission.PAYMENT,
        ),
        allowedPackages = setOf("com.phonepe.app"),
        exampleParamsHint = """{"amount":1800,"contacts":["Aarav","Meera","Rohan"],"note":"Pizza"}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val amount = (params["amount"] as? Number)?.toDouble()
            ?: return SkillResult.Failure("How much is the total bill? (provide 'amount')")
        @Suppress("UNCHECKED_CAST")
        val contacts = (params["contacts"] as? List<String>)
            ?: (params["people"] as? List<String>)
            ?: return SkillResult.Failure("Who are you splitting with? (provide 'contacts' list)")
        val note = params["note"] as? String ?: "Bill split"
        val includePayer = (params["includePayer"] as? Boolean) ?: true

        if (contacts.isEmpty()) return SkillResult.Failure("Contacts list is empty.")

        val totalHeads = contacts.size + (if (includePayer) 1 else 0)
        val perHead = (amount / totalHeads).toInt()
        val contactList = contacts.joinToString(", ")

        return SkillResult.NeedsConfirmation(
            prompt = "?? *Bill Split via PhonePe*\n\nTotal: *?${"%.0f".format(amount)}* for \"$note\"\nSplit $totalHeads ways = *?$perHead each*\nContacts: $contactList\n\nThis will draft UPI requests (not send them) in PhonePe.\n\nReply *YES* to proceed.",
            onConfirm = { executeSplit(runner, agent, amount, contacts, note, includePayer, perHead) }
        )
    }

    private suspend fun executeSplit(
        runner: SandboxedRunner,
        agent: ScreenAgent,
        amount: Double,
        contacts: List<String>,
        note: String,
        includePayer: Boolean,
        perHead: Int,
    ): SkillResult {
        val totalHeads = contacts.size + (if (includePayer) 1 else 0)

        runner.openApp("com.phonepe.app")
        runner.waitForApp("com.phonepe.app", timeoutMs = 7000)
        delay(900)
        runner.dismissPopups(2)
        delay(200)

        val results = mutableListOf<String>()
        for ((idx, contact) in contacts.withIndex()) {
            val goal = buildString {
                append("You are in PhonePe. Draft a UPI REQUEST (not a payment) for ?$perHead ")
                append("to the contact named \"$contact\" with the note \"$note\".\n\n")
                append("STEPS:\n")
                append("1. Look for a 'To Contact' / 'Send to Contact' / 'Request' option on the home screen.\n")
                append("2. If you see a 'Request' button/tab, TAP it. Otherwise tap 'To Contact' then look for 'Request' mode.\n")
                append("3. In the contact search, type \"$contact\" and tap the matching contact from the list.\n")
                append("4. In the amount field, enter $perHead.\n")
                append("5. If there is a note/message field, type \"$note\".\n")
                append("6. STOP before tapping 'Request' or 'Send Request' — do NOT submit.\n")
                append("7. Call done with summary 'Drafted request of ?$perHead to $contact'.\n\n")
                append("?? NEVER enter UPI PIN. NEVER make a payment. Only DRAFT the request.\n")
                append("?? If a UPI PIN screen appears, press back immediately.\n")
                if (idx < contacts.size - 1) {
                    append("After this contact, the next request will start from the PhonePe home — ")
                    append("so after calling done, it's OK to return to the home screen.")
                }
            }
            val stepResult = agent.executeGoal(runner, goal, maxSteps = 40)
            results += "• $contact ? ?$perHead : $stepResult"

            // Return to PhonePe home before next contact
            if (idx < contacts.size - 1) {
                repeat(3) { runner.pressBack(); delay(250) }
                delay(400)
            }
        }

        val summary = buildString {
            appendLine("?? *Bill Split: ?%.0f for \"$note\"*".format(amount))
            appendLine("Split across $totalHeads ${if (includePayer) "(incl. you)" else "(contacts only)"} = *?$perHead each*")
            appendLine()
            appendLine("Drafted requests:")
            results.forEach { appendLine(it) }
            appendLine()
            appendLine("?? Each request is *drafted but not sent*. Open PhonePe ? Requests tab to review and send.")
        }
        return SkillResult.Success(summary)
    }
}
