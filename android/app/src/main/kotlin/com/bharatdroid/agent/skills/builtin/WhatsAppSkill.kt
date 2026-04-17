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
                // Use typeInFieldWithHint first (more reliable than typeInFocused which
                // bypasses verification and append-mode safety)
                runner.typeInFieldWithHint("Search", contact)
                    || runner.typeReliably(contact)
                delay(1500) // wait for results to fully load

                // Step 4: Tap the exact contact from search results — first result only
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
                val chatOpened = runner.waitForAny(
                    "Type a message", "Message", "Type message", "iMessage",
                    timeoutMs = 4000,
                )
                if (chatOpened == null) {
                    // Chat didn't open — still on search results, tap more aggressively
                    val (w2, h2) = runner.getScreenSize()
                    runner.tapAtPoint(w2 / 2f, h2 * 0.28f)
                    val retryOpened = runner.waitForAny("Type a message", "Message", timeoutMs = 3000)

                    // FINAL FALLBACK: delegate to AI with crystal-clear instructions
                    // The AI can see the screen and will pick the right contact row,
                    // then type into the "Type a message" field at the bottom.
                    if (retryOpened == null) {
                        val aiGoal = buildString {
                            append("You are in WhatsApp. Search results for \"$contact\" are visible.\n\n")
                            append("STEPS (do EXACTLY this):\n")
                            append("1. TAP the contact row matching \"$contact\" (look for role=list-item with this name). ")
                            append("   DO NOT tap profile avatars on the far left. Tap the NAME/row center.\n")
                            append("2. WAIT — the chat screen opens with 'Type a message' at the BOTTOM of the screen.\n")
                            append("3. Find the EDITABLE FIELD with role=message-input or hint 'Type a message' at the BOTTOM.\n")
                            append("4. TAP that message field (NOT the search bar at the top).\n")
                            append("5. TYPE this EXACT text in that field: $message\n")
                            append("6. CALL done with summary 'Message typed for $contact'.\n\n")
                            append("⚠️ CRITICAL:\n")
                            append("- The search bar is at the TOP. The message field is at the BOTTOM.\n")
                            append("- NEVER type the message into the search bar.\n")
                            append("- NEVER press back — you will lose the chat.\n")
                            append("- NEVER press Send. Just type the message and stop.\n")
                            append("- If you see search results, TAP a contact row first.")
                        }
                        val aiResult = agent.executeGoal(runner, aiGoal, maxSteps = 10)
                        return if (shouldSend) {
                            runner.tapByText("Send")
                            delay(500)
                            SkillResult.Success("✅ Message sent to *$contact*: \"$message\"")
                        } else {
                            SkillResult.NeedsConfirmation(
                                prompt = "Message typed for *$contact*:\n\"$message\"\n\nReply *YES* to send it, or *NO* to cancel.\n\n(AI fallback used: $aiResult)",
                                onConfirm = {
                                    runner.tapByText("Send")
                                    delay(500)
                                    SkillResult.Success("✅ Message sent to *$contact*!")
                                },
                            )
                        }
                    }
                }
                delay(600) // let the chat transition animation fully finish

                // Step 6: Tap the message input bar at the bottom of the chat.
                // ⚠️  DO NOT call runner.pressBack() here — the chat is NOW OPEN.
                // pressBack from inside an open chat goes BACK to the chat list, closing the
                // chat entirely. That was the root cause of the "goes back after clicking
                // contact" bug: tap contact → chat opens → pressBack closes chat → type lands
                // in the search bar = wrong field, wrong screen.
                //
                // Instead, just tap directly on the message input bar to get focus there.
                val (ww, hh) = runner.getScreenSize()
                runner.tapAtPoint(ww * 0.45f, hh * 0.92f) // message bar, bottom-center
                delay(400)

                // Step 7: Type message in the chat input field
                // SAFETY CHECK: find the message-input field explicitly BEFORE typing.
                // Previously, typeInFieldWithHint("Type a message") would silently fall back
                // to the search bar at the top if the chat hadn't fully opened — that caused
                // "message typed into search bar" bug. Now we verify the field is at the BOTTOM
                // of the screen (y > 70% of height) before trusting it.
                val (sw, sh) = runner.getScreenSize()
                val msgField = runner.getClickableElements().firstOrNull { el ->
                    el.isEditable
                        && el.centerY > sh * 0.70f  // must be in bottom 30%
                        && (el.hint.contains("message", ignoreCase = true)
                            || el.hint.contains("type", ignoreCase = true)
                            || el.contentDescription.contains("message", ignoreCase = true))
                }
                if (msgField != null) {
                    // Tap to focus the verified message input, then type
                    runner.tapAtPoint(msgField.centerX.toFloat(), msgField.centerY.toFloat())
                    delay(350)
                }
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
        val (screenW, screenH) = runner.getScreenSize()
        val topZone = screenH * 0.12f // top 12% of screen — works across all screen sizes

        // Strategy 1: element with search in text/description, small, at top
        val elements = runner.getClickableElements()
        val searchEl = elements.firstOrNull { el ->
            val combined = (el.text + el.hint + el.contentDescription + el.viewId).lowercase()
            (combined.contains("search") || combined.contains("find"))
                && el.centerY < topZone
                && el.width < screenW * 0.25f
        }
        if (searchEl != null) {
            return runner.tapAtPoint(searchEl.centerX.toFloat(), searchEl.centerY.toFloat())
        }

        // Strategy 2: tapByText
        if (runner.tapByText("Search")) return true

        // Strategy 3: top-right corner area (where WhatsApp search icon usually is)
        return runner.tapAtPoint(screenW * 0.85f, screenH * 0.06f)
    }
}
