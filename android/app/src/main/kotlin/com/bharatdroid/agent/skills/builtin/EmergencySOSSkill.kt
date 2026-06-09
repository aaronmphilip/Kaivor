package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.ScreenAgent
import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

/**
 * EmergencySOSSkill - the dramatic demo.
 * Sends a WhatsApp message with live location to an emergency contact,
 * optionally places a call to them right after.
 *
 * Hackathon one-liner: "SOS - alert Mom."
 *
 * Example params:
 *   {"contact":"Mom","message":"I need help, sending my location","call":true}
 */
class EmergencySOSSkill : Skill {

    override val manifest = SkillManifest(
        id = "emergency_sos",
        name = "Emergency SOS",
        version = "1.0.0",
        description = "Sends an SOS WhatsApp message with live location to a contact and optionally places a call.",
        author = "bharatclaw-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK, Permission.SENSITIVE_READ,
        ),
        allowedPackages = setOf(
            "com.whatsapp",
            "com.google.android.dialer",
            "com.android.dialer",
            "com.samsung.android.dialer",
        ),
        exampleParamsHint = """{"contact":"Mom","message":"I need help","call":true}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val contact = params["contact"] as? String
            ?: return SkillResult.Failure("Who should I alert? (provide 'contact')")
        val message = params["message"] as? String
            ?: "SOS - I need help. Sharing my live location now."
        val shouldCall = (params["call"] as? Boolean) ?: true
        val shareLocation = (params["shareLocation"] as? Boolean) ?: true

        val callNote = if (shouldCall) " + place a call to them" else ""
        val locNote = if (shareLocation) " + share live location" else ""

        return SkillResult.NeedsConfirmation(
            prompt = "*EMERGENCY SOS*\n\nSend WhatsApp SOS message$locNote$callNote to *$contact*?\n\nMessage: \"$message\"\n\nThis will immediately send a message and${if (shareLocation) " share your live location" else ""}${if (shouldCall) " and call $contact" else ""}.\n\nReply *YES* to confirm.",
            onConfirm = { executeSOS(runner, agent, contact, message, shouldCall, shareLocation) }
        )
    }

    private suspend fun executeSOS(
        runner: SandboxedRunner,
        agent: ScreenAgent,
        contact: String,
        message: String,
        shouldCall: Boolean,
        shareLocation: Boolean,
    ): SkillResult {
        // --- 1. WhatsApp: find contact, send message + live location -------------
        runner.openApp("com.whatsapp")
        runner.waitForApp("com.whatsapp", timeoutMs = 6000)
        delay(700)
        runner.dismissPopups(1)
        delay(200)

        val waGoal = buildString {
            append("EMERGENCY: Send an SOS to \"$contact\" on WhatsApp. Speed matters.\n\n")
            append("STEPS:\n")
            append("1. If not already on the Chats tab, tap 'Chats' at the bottom.\n")
            append("2. Tap the search icon (magnifying glass, top-right).\n")
            append("3. Type \"$contact\" and tap their chat row from results. ")
            append("   Tap the NAME/center of the row, NOT the profile avatar on the far left.\n")
            append("4. Wait for the chat to open - you should see 'Type a message' at the BOTTOM.\n")
            append("5. Tap the message input at the BOTTOM of the screen (not the search bar at top).\n")
            append("6. Type EXACTLY this message: $message\n")
            append("7. Tap the Send button (paper-plane icon to the right of the input).\n")
            if (shareLocation) {
                append("8. AFTER sending - tap the PAPERCLIP / PLUS / attachment icon near the input.\n")
                append("9. Choose 'Location' from the attachment menu.\n")
                append("10. Tap 'Share live location' - pick '15 minutes' duration.\n")
                append("11. Tap 'Send' on the live-location preview.\n")
                append("12. Call done once live location message is sent.\n")
            } else {
                append("8. Call done once the SOS message is sent.\n")
            }
            append("\nIf any permission dialog appears (location, camera), tap 'Allow' or 'While using app'.\n")
            append("DO NOT go back to the chat list before completing all steps.")
        }
        val waResult = agent.executeGoal(runner, waGoal, maxSteps = 60)

        // --- 2. Optional: place a call via the dialer ---------------------------
        val callResult = if (shouldCall) {
            // Try the most common dialer packages until one opens
            val dialers = listOf("com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer")
            var opened = false
            for (pkg in dialers) {
                try {
                    runner.openApp(pkg)
                    if (runner.waitForApp(pkg, timeoutMs = 3500)) {
                        opened = true
                        break
                    }
                } catch (_: Exception) { /* try next */ }
            }
            if (!opened) {
                "(could not open dialer)"
            } else {
                delay(500)
                runner.dismissPopups(1)
                val dialGoal = buildString {
                    append("EMERGENCY: Call \"$contact\" from the dialer.\n\n")
                    append("STEPS:\n")
                    append("1. Tap the 'Contacts' tab or the search icon at the top.\n")
                    append("2. Type \"$contact\" and tap the matching contact.\n")
                    append("3. Tap the phone/call icon next to their number to start the call.\n")
                    append("4. Call done as soon as the call is dialing (don't wait for pick-up).\n")
                }
                agent.executeGoal(runner, dialGoal, maxSteps = 70)
            }
        } else "(call disabled)"

        val summary = buildString {
            appendLine("*SOS triggered for $contact*")
            appendLine()
            appendLine("*WhatsApp*: $waResult")
            if (shouldCall) {
                appendLine()
                appendLine("*Call*: $callResult")
            }
            appendLine()
            appendLine("Stay safe. Help is on the way.")
        }
        return SkillResult.Success(summary)
    }
}
