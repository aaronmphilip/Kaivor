package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.ScreenElement
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

                // Step 1: Make sure we're on the CHATS tab, not Status/Calls/Communities
                // WhatsApp sometimes opens on Status tab which breaks the search flow
                val screen0 = runner.readScreen()
                when {
                    screen0.contains("Type a message", ignoreCase = true) -> {
                        // Inside a chat — go back to chat list
                        runner.pressBack(); delay(500)
                    }
                    screen0.contains("Status", ignoreCase = true)
                        && !screen0.contains("Chats", ignoreCase = true) -> {
                        // On Status tab — tap Chats tab
                        runner.tapByText("Chats")
                        delay(400)
                    }
                }

                // Step 2: ALWAYS search — never tap from visible chat list
                val searchTapped = tapWhatsAppSearch(runner)
                if (!searchTapped) {
                    delay(500)
                    tapWhatsAppSearch(runner)
                }
                delay(600)

                // Step 3: Type ONLY the contact name in search — nothing else
                runner.typeInFocused(contact)
                    || runner.typeInFieldWithHint("Search", contact)
                delay(1500) // wait for results to fully load

                // Step 4: Tap the exact contact from search results — first result only
                val afterSearch = runner.readScreen()
                val elements = runner.getClickableElements()

                val searchElements = runner.getClickableElements()
                val (screenW, screenH) = runner.getScreenSize()

                val notAContact = setOf(
                    "messages", "contacts", "groups", "chats", "people",
                    "recent", "all contacts", "search", "cancel"
                )

                // Profile pictures in WhatsApp are small square/circular elements on the FAR LEFT
                // of each row (x < 15% of screen width). We MUST avoid tapping them.
                // The contact name element is wider and centred in the row.
                fun isProfilePic(el: ScreenElement): Boolean {
                    val isSmall = el.width < screenW * 0.15f || el.height < screenW * 0.15f
                    val isFarLeft = el.centerX < screenW * 0.15f
                    return isSmall && isFarLeft
                }

                // Find the best contact element — exact match > starts-with > contains
                val contactEl = searchElements.firstOrNull { el ->
                    el.text.trim().equals(contact.trim(), ignoreCase = true)
                        && el.isClickable && !isProfilePic(el)
                        && el.centerY > screenH * 0.12f
                        && notAContact.none { el.text.trim().equals(it, ignoreCase = true) }
                } ?: searchElements.firstOrNull { el ->
                    el.text.trim().startsWith(contact.trim(), ignoreCase = true)
                        && el.isClickable && !isProfilePic(el)
                        && el.text.length < 80 && el.centerY > screenH * 0.12f
                        && notAContact.none { el.text.trim().equals(it, ignoreCase = true) }
                } ?: searchElements.firstOrNull { el ->
                    el.text.contains(contact.trim(), ignoreCase = true)
                        && el.isClickable && !isProfilePic(el)
                        && el.text.length < 80 && el.centerY > screenH * 0.12f
                        && notAContact.none { el.text.trim().equals(it, ignoreCase = true) }
                }

                if (contactEl != null) {
                    // Tap the RIGHT HALF of the row to guarantee we hit the name, not the avatar
                    val tapX = (contactEl.centerX + screenW * 0.15f).coerceAtMost(screenW * 0.85f)
                    runner.tapAtPoint(tapX.toFloat(), contactEl.centerY.toFloat())
                } else {
                    // Fallback: tap the right-centre of first result row below search bar
                    runner.tapAtPoint(screenW * 0.55f, screenH * 0.28f)
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

                // Step 6: Clear any stale focus from search bar BEFORE typing the message
                // When WhatsApp opens a chat after searching, the search bar may still
                // have keyboard focus. Pressing back dismisses the keyboard/search focus,
                // then tapping the message field gets clean focus there.
                runner.pressBack() // dismiss keyboard/search focus
                delay(300)

                // Now tap the message input field at the bottom to get fresh focus there
                val (ww, hh) = runner.getScreenSize()
                runner.tapAtPoint(ww * 0.45f, hh * 0.92f) // tap message bar area
                delay(300)

                // Step 7: Type message in the chat input field
                // PRIMARY: use typeInFieldWithHint to target the actual "Type a message" field
                // This avoids the bug where message was typed in the search bar instead
                val typedOk = runner.typeInFieldWithHint("Type a message", message)
                    || runner.typeInFieldWithHint("Message", message)
                    || runner.typeInFieldWithHint("Type message", message)

                if (!typedOk) {
                    // Fallback: find message input by position + hint, then typeReliably
                    val (w, h) = runner.getScreenSize()
                    val msgInput = runner.getClickableElements().firstOrNull { el ->
                        el.isEditable
                            && el.centerY > h * 0.70f  // bottom 30% of screen
                            && (el.hint.contains("message", ignoreCase = true)
                                || el.hint.contains("type", ignoreCase = true)
                                || el.contentDescription.contains("message", ignoreCase = true))
                    }
                    if (msgInput != null) {
                        runner.tapAtPoint(msgInput.centerX.toFloat(), msgInput.centerY.toFloat())
                    } else {
                        // Last resort: tap at the known message bar position
                        runner.tapAtPoint(w * 0.45f, h * 0.92f)
                    }
                    delay(400)
                    runner.typeReliably(message)
                }
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
