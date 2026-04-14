package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class WhatsAppSkill : Skill {

    override val manifest = SkillManifest(
        id = "whatsapp",
        name = "WhatsApp Messaging",
        version = "9.0.0",
        description = "Send WhatsApp messages, read chats — searches for contact first, never taps random chats",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK, Permission.SENSITIVE_READ,
        ),
        allowedPackages = setOf("com.whatsapp"),
        exampleParamsHint = """{"action": "send", "contact": "Mom", "message": "Coming home"}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase() ?: "send"
        val contact = params["contact"] as? String ?: ""
        val message = params["message"] as? String ?: ""
        val shouldSend = (params["send"] as? Boolean) ?: false // default: type only, don't auto-send

        if (action == "send" && contact.isBlank()) {
            return SkillResult.Failure("Who should I message? (Please give a contact name)")
        }

        runner.openApp("com.whatsapp")
        runner.waitForApp("com.whatsapp", timeoutMs = 6000)
        delay(600)
        runner.dismissPopups(1)
        delay(300)

        return when (action) {
            "read" -> {
                val screen = runner.readScreen()
                SkillResult.Success("Recent WhatsApp chats:\n```\n${screen.take(800)}\n```")
            }

            "send", "message" -> {
                if (contact.isBlank()) return SkillResult.Failure("Who should I message?")
                if (message.isBlank()) return SkillResult.Failure("What should I say?")

                // Step 1: Make sure we're on the chat list — go back if inside a chat
                val screen0 = runner.readScreen()
                val inAChat = screen0.contains("Type a message", ignoreCase = true)
                    || screen0.contains("message", ignoreCase = true) && screen0.contains("Audio", ignoreCase = true)
                if (inAChat) {
                    runner.pressBack()
                    delay(500)
                }

                // Step 2: ALWAYS search — never tap from visible chat list
                // Find and tap the search icon (magnifying glass at top)
                val searchTapped = tapWhatsAppSearch(runner)
                if (!searchTapped) {
                    // Retry after a brief wait
                    delay(500)
                    tapWhatsAppSearch(runner)
                }
                delay(500)

                // Step 3: Type contact name in search
                runner.typeInFocused(contact)
                    || runner.typeInFieldWithHint("Search", contact)
                delay(1200) // wait for results

                // Step 4: Tap the exact contact from search results — first result only
                val afterSearch = runner.readScreen()
                val elements = runner.getClickableElements()

                // Find element that matches contact name (not "Messages", "Contacts" headers)
                val contactEl = elements.firstOrNull { el ->
                    el.text.contains(contact, ignoreCase = true)
                        && el.text.length < 60
                        && el.isClickable
                        && !el.text.equals("Messages", ignoreCase = true)
                        && !el.text.equals("Contacts", ignoreCase = true)
                }

                if (contactEl != null) {
                    runner.tapAtPoint(contactEl.centerX.toFloat(), contactEl.centerY.toFloat())
                } else {
                    val (_, h) = runner.getScreenSize()
                    runner.tapAtPoint(runner.getScreenSize().first / 2f, h * 0.30f)
                }

                // Step 5: WAIT for chat to fully open before typing
                // This is the key fix — without this wait, the search bar is still
                // focused and the message gets typed there instead of the chat input
                val chatOpened = runner.waitForAny(
                    "Type a message", "Message", "Type message", "iMessage",
                    timeoutMs = 4000,
                )
                if (chatOpened == null) {
                    // Chat didn't open — we might still be on search results
                    // Try tapping the first result more aggressively
                    val (w2, h2) = runner.getScreenSize()
                    runner.tapAtPoint(w2 / 2f, h2 * 0.28f)
                    runner.waitForAny("Type a message", "Message", timeoutMs = 3000)
                }
                delay(400)

                // Step 6: Explicitly tap the message input bar (bottom of chat screen)
                // DO NOT use typeInFocused() — the search bar may still be focused
                // Find the message input specifically: editable, at bottom, has "message" hint
                val (w, h) = runner.getScreenSize()
                val msgInput = runner.getClickableElements().firstOrNull { el ->
                    el.isEditable
                        && el.centerY > h * 0.80f  // bottom of screen
                        && (el.hint.contains("message", ignoreCase = true)
                            || el.hint.contains("type", ignoreCase = true)
                            || el.contentDescription.contains("message", ignoreCase = true))
                }

                if (msgInput != null) {
                    runner.tapAtPoint(msgInput.centerX.toFloat(), msgInput.centerY.toFloat())
                } else {
                    // Fallback: tap at the known message bar position (bottom 12% of screen)
                    runner.tapAtPoint(w * 0.45f, h * 0.92f)
                }
                delay(400)

                // Now type — use typeReliably with clipboard fallback for older phones
                runner.typeReliably(message)
                delay(300)

                // Step 6: By default — do NOT send. User said "type only, don't send"
                // Only send if explicitly requested via shouldSend=true
                if (shouldSend) {
                    runner.tapByText("Send")
                        || run {
                            val sendEl = runner.getClickableElements().firstOrNull { el ->
                                val t = (el.text + el.contentDescription).lowercase()
                                t.contains("send") && !t.contains("audio")
                            }
                            if (sendEl != null) runner.tapAtPoint(sendEl.centerX.toFloat(), sendEl.centerY.toFloat()) else false
                        }
                    delay(500)
                    SkillResult.Success("✅ Message sent to *$contact*: \"$message\"")
                } else {
                    // Message is typed, waiting for user to review and send manually
                    SkillResult.NeedsConfirmation(
                        prompt = "Message typed for *$contact*:\n\"$message\"\n\nReply *YES* to send it, or *NO* to cancel.",
                        onConfirm = {
                            runner.tapByText("Send")
                                || run {
                                    val sendEl = runner.getClickableElements().firstOrNull { el ->
                                        val t = (el.text + el.contentDescription).lowercase()
                                        t.contains("send") && !t.contains("audio")
                                    }
                                    if (sendEl != null) runner.tapAtPoint(sendEl.centerX.toFloat(), sendEl.centerY.toFloat()) else false
                                }
                            delay(500)
                            SkillResult.Success("✅ Message sent to *$contact*!")
                        },
                    )
                }
            }

            else -> {
                val goal = params["goal"] as? String
                    ?: "Do this in WhatsApp: $action ${contact.ifBlank { "" }} ${message.ifBlank { "" }}".trim()
                val result = agent.executeGoal(runner, goal, maxSteps = 20)
                SkillResult.Success(result)
            }
        }
    }

    /** Find and tap the WhatsApp search icon — tries multiple strategies */
    private suspend fun tapWhatsAppSearch(runner: SandboxedRunner): Boolean {
        // Strategy 1: element with search in text/description, small, at top
        val elements = runner.getClickableElements()
        val searchEl = elements.firstOrNull { el ->
            val combined = (el.text + el.hint + el.contentDescription + el.viewId).lowercase()
            (combined.contains("search") || combined.contains("find"))
                && el.centerY < 400
                && el.width < 250
        }
        if (searchEl != null) {
            return runner.tapAtPoint(searchEl.centerX.toFloat(), searchEl.centerY.toFloat())
        }

        // Strategy 2: tapByText
        if (runner.tapByText("Search")) return true

        // Strategy 3: top-right corner area (where WhatsApp search icon usually is)
        val (w, _) = runner.getScreenSize()
        return runner.tapAtPoint(w * 0.85f, 130f)
    }
}
