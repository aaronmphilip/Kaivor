package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.ScreenAgent
import com.bharatdroid.agent.ScreenElement
import com.bharatdroid.agent.skills.Permission
import com.bharatdroid.agent.skills.SandboxedRunner
import com.bharatdroid.agent.skills.Skill
import com.bharatdroid.agent.skills.SkillContext
import com.bharatdroid.agent.skills.SkillManifest
import com.bharatdroid.agent.skills.SkillResult
import kotlinx.coroutines.delay

class WhatsAppSkill : Skill {

    override val manifest = SkillManifest(
        id = "whatsapp",
        name = "WhatsApp Messaging",
        version = "10.0.0",
        description = "Send WhatsApp messages or documents. Opens the exact chat first, can attach files through the document picker, and avoids tapping random chats.",
        author = "bharatclaw-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP,
            Permission.READ_SCREEN,
            Permission.TAP,
            Permission.TYPE,
            Permission.SCROLL,
            Permission.NAVIGATE_BACK,
            Permission.SENSITIVE_READ,
        ),
        allowedPackages = setOf("com.whatsapp"),
        exampleParamsHint = """{"action":"send_file","contact":"Mom","file":"invoice.pdf","caption":"Please review this"}""",
        uiKnowledge = "WhatsApp home shows a list of recent chats. There is a search icon at top-right (magnifying glass). Tap the search icon to search for a contact by name. Tap the contact in search results to open the chat. The message input field is at the BOTTOM of the screen (below the chat bubbles). The attachment button is a paperclip or '+' icon to the LEFT of the message field. Tapping it shows: Document, Camera, Gallery, Audio, Location, Contact options. To send a file, tap attachment ? Document ? navigate to file ? tap it. For multiple files, long-press the first file, then tap others to multi-select, then tap Send.",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase() ?: "send"
        val contact = params["contact"] as? String ?: ""
        val message = params["message"] as? String ?: ""
        val fileQuery = firstNonBlank(
            params["file"] as? String,
            params["document"] as? String,
            params["query"] as? String,
            params["title"] as? String,
        )
        // Multi-file: "files" param is comma-separated, e.g. "invoice.pdf,resume.pdf"
        val filesParam = params["files"] as? String ?: ""
        val fileList = filesParam.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val folder = params["folder"] as? String ?: ""
        val caption = firstNonBlank(params["caption"] as? String, message)
        val shouldSend = (params["send"] as? Boolean) ?: false

        when {
            action in messageActions && contact.isBlank() ->
                return SkillResult.Failure("Who should I message? (Please give a contact name)")

            action in messageActions && message.isBlank() ->
                return SkillResult.Failure("What should I say?")

            action in fileActions && contact.isBlank() ->
                return SkillResult.Failure("Who should I send the file to on WhatsApp?")
        }

        runner.openApp("com.whatsapp")
        runner.waitForApp("com.whatsapp", timeoutMs = 6000)
        delay(150)
        runner.dismissPopups(1)
        delay(100)

        return when (action) {
            "read" -> {
                val screen = runner.readScreen()
                SkillResult.Success("Recent WhatsApp chats:\n```\n${screen.take(800)}\n```")
            }

            in messageActions -> sendMessage(
                runner = runner,
                agent = agent,
                contact = contact,
                message = message,
                shouldSend = shouldSend,
            )

            in fileActions -> if (fileList.size > 1) {
                sendMultipleFiles(
                    runner = runner,
                    agent = agent,
                    contact = contact,
                    fileList = fileList,
                    folder = folder,
                    caption = caption,
                    shouldSend = shouldSend,
                )
            } else {
                sendFile(
                    runner = runner,
                    agent = agent,
                    contact = contact,
                    fileQuery = fileQuery,
                    folder = folder,
                    caption = caption,
                    shouldSend = shouldSend,
                )
            }

            else -> {
                val goal = params["goal"] as? String
                    ?: "Do this in WhatsApp: $action ${contact.ifBlank { "" }} ${message.ifBlank { fileQuery }}".trim()
                val result = agent.executeGoal(runner, goal, maxSteps = 55)
                SkillResult.Success(result)
            }
        }
    }

    private suspend fun sendMessage(
        runner: SandboxedRunner,
        agent: ScreenAgent,
        contact: String,
        message: String,
        shouldSend: Boolean,
    ): SkillResult {
        val chatOpened = openContactChat(runner, agent, contact)
        if (!chatOpened) {
            return SkillResult.Failure("I couldn't open the chat for $contact yet.")
        }

        val typed = typeChatMessage(runner, message)
        if (!typed) {
            val fallback = agent.executeGoal(
                runner,
                buildMessageTypingFallbackGoal(contact, message),
                maxSteps = 65,
            )
            val screen = runner.readScreen()
            val verifyChunk = message.take(8)
            if (verifyChunk.length >= 3 && !screen.contains(verifyChunk, ignoreCase = true)) {
                return SkillResult.Failure("I reached $contact's chat, but I couldn't type the message yet. $fallback")
            }
        }

        return if (shouldSend) {
            if (!tapSendButton(runner)) {
                SkillResult.Failure("The message is typed for $contact, but I couldn't find the send button.")
            } else {
                delay(500)
                SkillResult.Success("Message sent to *$contact*: \"$message\"")
            }
        } else {
            SkillResult.NeedsConfirmation(
                prompt = "Message typed for *$contact*:\n\"$message\"\n\nReply *YES* to send it, or *NO* to cancel.",
                onConfirm = {
                    if (!tapSendButton(runner)) {
                        SkillResult.Failure("The message is ready, but I couldn't find the send button.")
                    } else {
                        delay(500)
                        SkillResult.Success("Message sent to *$contact*!")
                    }
                },
            )
        }
    }

    private suspend fun sendFile(
        runner: SandboxedRunner,
        agent: ScreenAgent,
        contact: String,
        fileQuery: String,
        folder: String,
        caption: String,
        shouldSend: Boolean,
    ): SkillResult {
        val chatOpened = openContactChat(runner, agent, contact)
        if (!chatOpened) {
            return SkillResult.Failure("I couldn't open the chat for $contact yet.")
        }

        val pickerOpened = openDocumentPicker(runner, agent)
        if (!pickerOpened) {
            return SkillResult.Failure("I reached $contact's chat, but I couldn't open the file picker yet.")
        }

        val fileChosen = pickFileFromPicker(runner, agent, fileQuery, folder)
        if (!fileChosen) {
            return SkillResult.Failure("I opened the file picker, but I couldn't select the right file yet.")
        }

        if (caption.isNotBlank()) {
            typeAttachmentCaption(runner, caption)
        }

        val screen = runner.readScreen()
        if (!looksLikeFileReadyToSend(screen, fileQuery, caption)) {
            return SkillResult.Failure(
                "I reached $contact's chat, but I couldn't get the file attached and ready to send yet.",
            )
        }

        val targetFile = describeTargetFile(fileQuery, folder)
        return if (shouldSend) {
            if (!tapSendButton(runner)) {
                SkillResult.Failure("The file is ready for $contact, but I couldn't find the send button.")
            } else {
                delay(600)
                SkillResult.Success("Sent $targetFile to *$contact* on WhatsApp.")
            }
        } else {
            SkillResult.NeedsConfirmation(
                prompt = buildFileConfirmationPrompt(contact, targetFile, caption),
                onConfirm = {
                    if (!tapSendButton(runner)) {
                        SkillResult.Failure("The file is attached, but I couldn't find the send button.")
                    } else {
                        delay(600)
                        SkillResult.Success("Sent $targetFile to *$contact* on WhatsApp.")
                    }
                },
            )
        }
    }

    private suspend fun openContactChat(
        runner: SandboxedRunner,
        agent: ScreenAgent,
        contact: String,
    ): Boolean {
        prepareChatsTab(runner)

        var searchTapped = tapWhatsAppSearch(runner)
        if (!searchTapped) {
            delay(400)
            searchTapped = tapWhatsAppSearch(runner)
        }
        if (!searchTapped) return false

        delay(600)
        val typed = runner.typeInFieldWithHint("Search", contact) || runner.typeReliably(contact)
        if (!typed) return false

        delay(1400)
        tapBestSearchResult(runner, contact)
        if (waitForChatToOpen(runner)) return true

        agent.executeGoal(runner, buildContactFallbackGoal(contact), maxSteps = 65)
        return waitForChatToOpen(runner)
    }

    private suspend fun openDocumentPicker(
        runner: SandboxedRunner,
        agent: ScreenAgent,
    ): Boolean {
        if (isFilePickerVisible(runner)) return true

        val attachmentTapped = tapAttachmentButton(runner) ||
            agent.tapSmartly(runner, "Tap the attachment, paperclip, plus, or add-file button in this WhatsApp chat.")
        if (!attachmentTapped) return false

        delay(700)
        if (isFilePickerVisible(runner) || looksLikeFileReadyToSend(runner.readScreen(), "", "")) {
            return true
        }

        val documentTapped = tapDocumentOption(runner) ||
            agent.tapSmartly(runner, "Tap the Document, File, Browse, or Files option to attach a file in WhatsApp.")
        if (!documentTapped) return false

        delay(900)
        return waitForFilePicker(runner) || looksLikeFileReadyToSend(runner.readScreen(), "", "")
    }

    private suspend fun pickFileFromPicker(
        runner: SandboxedRunner,
        agent: ScreenAgent,
        fileQuery: String,
        folder: String,
    ): Boolean {
        if (looksLikeFileReadyToSend(runner.readScreen(), fileQuery, "")) return true
        if (!waitForFilePicker(runner)) return false

        if (folder.isNotBlank() && !runner.readScreen().contains(folder, ignoreCase = true)) {
            val folderTapped = runner.tapByText(folder) ||
                agent.tapSmartly(runner, "Open the $folder location in this Android file picker.")
            if (folderTapped) {
                delay(700)
            }
        } else if (folder.isNotBlank() && runner.tapByText(folder)) {
            delay(700)
        }

        val tapped = if (fileQuery.isNotBlank()) {
            val searchOpened = tapPickerSearch(runner)
            if (searchOpened) {
                delay(350)
                val typed = runner.typeInFieldWithHint("Search", fileQuery) || runner.typeReliably(fileQuery)
                if (typed) {
                    delay(900)
                    runner.pressEnter()
                }
                delay(500)
            }
            tapMatchingFileResult(runner, fileQuery) ||
                agent.tapSmartly(
                    runner,
                    buildTapFileInstruction(fileQuery, folder),
                )
        } else {
            tapRecentDocumentResult(runner) ||
                agent.tapSmartly(
                    runner,
                    "Tap the most recent PDF or document file in this file picker. Prefer a file row, not a folder or category.",
                )
        }

        if (!tapped) return false
        return waitForPickerToClose(runner)
    }

    private suspend fun sendMultipleFiles(
        runner: SandboxedRunner,
        agent: ScreenAgent,
        contact: String,
        fileList: List<String>,
        folder: String,
        caption: String,
        shouldSend: Boolean,
    ): SkillResult {
        val chatOpened = openContactChat(runner, agent, contact)
        if (!chatOpened) return SkillResult.Failure("I couldn't open the chat for $contact.")

        val pickerOpened = openDocumentPicker(runner, agent)
        if (!pickerOpened) return SkillResult.Failure("I reached $contact's chat but couldn't open the file picker.")

        if (!waitForFilePicker(runner)) return SkillResult.Failure("File picker didn't appear.")

        // Long-press first file to enter multi-select mode, then tap the rest
        val firstEl = findFileElement(runner, fileList[0])
            ?: return SkillResult.Failure("Couldn't find file: ${fileList[0]}")

        runner.longPressAt(firstEl.centerX.toFloat(), firstEl.centerY.toFloat())
        delay(700)

        for (filename in fileList.drop(1)) {
            val el = findFileElement(runner, filename)
            if (el != null) {
                runner.tapAtPoint(el.centerX.toFloat(), el.centerY.toFloat())
                delay(400)
            }
        }

        // Tap the floating "Open" / checkmark / send button that appears after multi-select
        val confirmed = runner.tapByText("Open")
            || runner.tapByText("Done")
            || runner.tapByText("Select")
            || runner.getClickableElements().firstOrNull { el ->
                val cd = el.contentDescription.lowercase()
                (cd.contains("open") || cd.contains("done") || cd.contains("select"))
                    && el.centerY > runner.getScreenSize().second * 0.80f
            }?.let { el ->
                runner.tapAtPoint(el.centerX.toFloat(), el.centerY.toFloat())
            } != null

        if (!confirmed) return SkillResult.Failure("Files selected but I couldn't confirm the selection.")
        delay(800)

        if (caption.isNotBlank()) typeAttachmentCaption(runner, caption)

        val fileDesc = "${fileList.size} files (${fileList.joinToString(", ")})"
        return if (shouldSend) {
            if (!tapSendButton(runner)) {
                SkillResult.Failure("Files ready for $contact but I couldn't find the send button.")
            } else {
                delay(600)
                SkillResult.Success("Sent $fileDesc to *$contact* on WhatsApp.")
            }
        } else {
            SkillResult.NeedsConfirmation(
                prompt = "$fileDesc ready for *$contact*.\n\nReply *YES* to send, *NO* to cancel.",
                onConfirm = {
                    if (!tapSendButton(runner)) {
                        SkillResult.Failure("Files attached but couldn't find the send button.")
                    } else {
                        delay(600)
                        SkillResult.Success("Sent $fileDesc to *$contact* on WhatsApp.")
                    }
                },
            )
        }
    }

    private fun findFileElement(runner: SandboxedRunner, filename: String): ScreenElement? {
        if (filename.isBlank()) return null
        val tokens = filename.split(Regex("[^A-Za-z0-9._-]+"))
            .filter { it.length >= 2 }
            .map { it.lowercase() }
        if (tokens.isEmpty()) return null

        val blocked = setOf("search", "recent", "recents", "downloads", "documents",
            "images", "videos", "audio", "files", "cancel", "back", "browse")

        return runner.getClickableElements()
            .asSequence()
            .filter { it.isClickable && it.centerY > 120 }
            .filter { el ->
                val text = (el.text + " " + el.contentDescription).trim()
                text.isNotBlank() && blocked.none { b -> text.equals(b, ignoreCase = true) }
            }
            .maxByOrNull { el ->
                val text = (el.text + " " + el.contentDescription).lowercase()
                val matches = tokens.count { t -> text.contains(t) }
                val fileBoost = if (text.contains(".")) 2 else 0
                matches * 10 + fileBoost
            }
            ?.takeIf { el ->
                val text = (el.text + " " + el.contentDescription).lowercase()
                tokens.any { t -> text.contains(t) }
            }
    }

    private suspend fun prepareChatsTab(runner: SandboxedRunner) {
        val screen = runner.readScreen()
        when {
            screen.contains("Type a message", ignoreCase = true) -> {
                runner.pressBack()
                delay(500)
            }

            screen.contains("Status", ignoreCase = true) && !screen.contains("Chats", ignoreCase = true) ->
                if (runner.tapByText("Chats")) delay(400)

            screen.contains("Updates", ignoreCase = true) && !screen.contains("Chats", ignoreCase = true) ->
                if (runner.tapByText("Chats")) delay(400)

            screen.contains("Calls", ignoreCase = true) && !screen.contains("Chats", ignoreCase = true) ->
                if (runner.tapByText("Chats")) delay(400)
        }
    }

    private suspend fun tapAttachmentButton(runner: SandboxedRunner): Boolean {
        if (runner.tapByText("Attach")) return true
        if (runner.tapByText("Attachment")) return true

        val (screenW, screenH) = runner.getScreenSize()
        val attachEl = runner.getClickableElements()
            .asSequence()
            .filter { it.isClickable }
            .filter { el ->
                val combined = (el.text + " " + el.contentDescription + " " + el.hint).lowercase()
                (combined.contains("attach") ||
                    combined.contains("paperclip") ||
                    combined.contains("add") ||
                    combined.contains("plus")) &&
                    el.centerY > screenH * 0.55f
            }
            .maxByOrNull { it.centerY * 10 + (screenW - it.centerX) }
            ?: return false

        return runner.tapAtPoint(attachEl.centerX.toFloat(), attachEl.centerY.toFloat())
    }

    private suspend fun tapDocumentOption(runner: SandboxedRunner): Boolean {
        if (runner.tapByText("Document")) return true
        if (runner.tapByText("Documents")) return true
        if (runner.tapByText("File")) return true
        if (runner.tapByText("Files")) return true
        if (runner.tapByText("Browse")) return true

        val optionEl = runner.getClickableElements().firstOrNull { el ->
            val combined = (el.text + " " + el.contentDescription).lowercase()
            combined.contains("document") ||
                combined.contains("file") ||
                combined.contains("browse")
        } ?: return false

        return runner.tapAtPoint(optionEl.centerX.toFloat(), optionEl.centerY.toFloat())
    }

    private suspend fun tapPickerSearch(runner: SandboxedRunner): Boolean {
        if (!isFilePickerVisible(runner)) return false
        if (runner.tapByText("Search")) return true

        val (_, screenH) = runner.getScreenSize()
        val searchEl = runner.getClickableElements().firstOrNull { el ->
            val combined = (el.text + " " + el.contentDescription + " " + el.hint).lowercase()
            (combined.contains("search") || combined.contains("find")) &&
                el.centerY < screenH * 0.18f
        } ?: return false

        return runner.tapAtPoint(searchEl.centerX.toFloat(), searchEl.centerY.toFloat())
    }

    private suspend fun tapMatchingFileResult(
        runner: SandboxedRunner,
        fileQuery: String,
    ): Boolean {
        if (fileQuery.isBlank()) return false

        val tokens = fileQuery
            .split(Regex("[^A-Za-z0-9._-]+"))
            .filter { it.length >= 2 }
            .map { it.lowercase() }
        if (tokens.isEmpty()) return false

        val blocked = setOf(
            "search",
            "recent",
            "recents",
            "downloads",
            "browse",
            "documents",
            "images",
            "videos",
            "audio",
            "files",
            "cancel",
            "back",
        )

        val candidate = runner.getClickableElements()
            .asSequence()
            .filter { it.isClickable }
            .filter { it.centerY > 120 }
            .filter { el ->
                val text = (el.text + " " + el.contentDescription).trim()
                text.isNotBlank() && blocked.none { blockedWord -> text.equals(blockedWord, ignoreCase = true) }
            }
            .map { el ->
                val text = (el.text + " " + el.contentDescription).lowercase()
                val tokenMatches = tokens.count { token -> text.contains(token) }
                val fileBoost = if (text.contains(".")) 2 else 0
                val score = tokenMatches * 10 + fileBoost
                el to score
            }
            .filter { (_, score) -> score > 0 }
            .maxByOrNull { (_, score) -> score }
            ?.first
            ?: return false

        return runner.tapAtPoint(candidate.centerX.toFloat(), candidate.centerY.toFloat())
    }

    private suspend fun tapRecentDocumentResult(runner: SandboxedRunner): Boolean {
        val blocked = setOf(
            "search",
            "recent",
            "recents",
            "downloads",
            "browse",
            "documents",
            "images",
            "videos",
            "audio",
            "files",
            "cancel",
            "back",
        )
        val fileExtensions = listOf(".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt")

        val candidate = runner.getClickableElements()
            .asSequence()
            .filter { it.isClickable }
            .filter { it.centerY > 120 }
            .filter { el ->
                val text = (el.text + " " + el.contentDescription).trim()
                text.isNotBlank() &&
                    blocked.none { blockedWord -> text.equals(blockedWord, ignoreCase = true) }
            }
            .sortedBy { it.centerY }
            .firstOrNull { el ->
                val text = (el.text + " " + el.contentDescription).lowercase()
                fileExtensions.any { text.contains(it) } || text.length >= 6
            }
            ?: return false

        return runner.tapAtPoint(candidate.centerX.toFloat(), candidate.centerY.toFloat())
    }

    private suspend fun tapBestSearchResult(runner: SandboxedRunner, contact: String) {
        val elements = runner.getClickableElements()
        val (screenW, screenH) = runner.getScreenSize()
        val notAContact = setOf(
            "messages",
            "contacts",
            "groups",
            "chats",
            "people",
            "recent",
            "all contacts",
            "search",
            "cancel",
        )

        fun isProfilePicture(el: ScreenElement): Boolean {
            val isSmall = el.width < screenW * 0.15f || el.height < screenW * 0.15f
            val isFarLeft = el.centerX < screenW * 0.15f
            return isSmall && isFarLeft
        }

        val contactEl = elements.firstOrNull { el ->
            el.text.trim().equals(contact.trim(), ignoreCase = true) &&
                el.isClickable &&
                !isProfilePicture(el) &&
                el.centerY > screenH * 0.12f &&
                notAContact.none { el.text.trim().equals(it, ignoreCase = true) }
        } ?: elements.firstOrNull { el ->
            el.text.trim().startsWith(contact.trim(), ignoreCase = true) &&
                el.isClickable &&
                !isProfilePicture(el) &&
                el.text.length < 80 &&
                el.centerY > screenH * 0.12f &&
                notAContact.none { el.text.trim().equals(it, ignoreCase = true) }
        } ?: elements.firstOrNull { el ->
            el.text.contains(contact.trim(), ignoreCase = true) &&
                el.isClickable &&
                !isProfilePicture(el) &&
                el.text.length < 80 &&
                el.centerY > screenH * 0.12f &&
                notAContact.none { el.text.trim().equals(it, ignoreCase = true) }
        }

        if (contactEl != null) {
            val tapX = (contactEl.centerX + screenW * 0.15f).coerceAtMost(screenW * 0.85f)
            runner.tapAtPoint(tapX.toFloat(), contactEl.centerY.toFloat())
        } else {
            runner.tapAtPoint(screenW * 0.55f, screenH * 0.28f)
        }
    }

    private suspend fun waitForChatToOpen(runner: SandboxedRunner): Boolean {
        val opened = runner.waitForAny(
            "Type a message",
            "Message",
            "Type message",
            timeoutMs = 4000,
        )
        if (opened != null) return true

        val (screenW, screenH) = runner.getScreenSize()
        runner.tapAtPoint(screenW / 2f, screenH * 0.28f)
        return runner.waitForAny("Type a message", "Message", timeoutMs = 3000) != null
    }

    private suspend fun typeChatMessage(runner: SandboxedRunner, message: String): Boolean {
        focusBottomMessageField(runner)
        delay(350)

        val typed = runner.typeInFieldWithHint("Type a message", message) ||
            runner.typeInFieldWithHint("Message", message) ||
            runner.typeInFieldWithHint("Type message", message)
        if (typed) return true

        val (screenW, screenH) = runner.getScreenSize()
        val msgInput = runner.getClickableElements().firstOrNull { el ->
            el.isEditable &&
                el.centerY > screenH * 0.70f &&
                (el.hint.contains("message", ignoreCase = true) ||
                    el.hint.contains("type", ignoreCase = true) ||
                    el.contentDescription.contains("message", ignoreCase = true))
        }
        if (msgInput != null) {
            runner.tapAtPoint(msgInput.centerX.toFloat(), msgInput.centerY.toFloat())
        } else {
            runner.tapAtPoint(screenW * 0.45f, screenH * 0.92f)
        }
        delay(350)
        return runner.typeReliably(message)
    }

    private suspend fun focusBottomMessageField(runner: SandboxedRunner) {
        val (screenW, screenH) = runner.getScreenSize()
        val msgField = runner.getClickableElements().firstOrNull { el ->
            el.isEditable &&
                el.centerY > screenH * 0.70f &&
                (el.hint.contains("message", ignoreCase = true) ||
                    el.hint.contains("type", ignoreCase = true) ||
                    el.contentDescription.contains("message", ignoreCase = true))
        }
        if (msgField != null) {
            runner.tapAtPoint(msgField.centerX.toFloat(), msgField.centerY.toFloat())
        } else {
            runner.tapAtPoint(screenW * 0.45f, screenH * 0.92f)
        }
    }

    private suspend fun typeAttachmentCaption(
        runner: SandboxedRunner,
        caption: String,
    ): Boolean {
        if (caption.isBlank()) return true

        val typed = runner.typeInFieldWithHint("Add a caption", caption) ||
            runner.typeInFieldWithHint("Caption", caption) ||
            runner.typeInFieldWithHint("Type a message", caption)
        if (typed) return true

        val (screenW, screenH) = runner.getScreenSize()
        val captionField = runner.getClickableElements().firstOrNull { el ->
            el.isEditable &&
                el.centerY > screenH * 0.50f &&
                (el.hint.contains("caption", ignoreCase = true) ||
                    el.hint.contains("message", ignoreCase = true) ||
                    el.contentDescription.contains("caption", ignoreCase = true))
        }
        if (captionField != null) {
            runner.tapAtPoint(captionField.centerX.toFloat(), captionField.centerY.toFloat())
        } else {
            runner.tapAtPoint(screenW * 0.5f, screenH * 0.84f)
        }
        delay(350)
        return runner.typeReliably(caption)
    }

    private suspend fun waitForFilePicker(runner: SandboxedRunner, timeoutMs: Long = 5000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isFilePickerVisible(runner)) return true
            delay(250)
        }
        return false
    }

    private suspend fun waitForPickerToClose(runner: SandboxedRunner, timeoutMs: Long = 6000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val screen = runner.readScreen()
            if (looksLikeFileReadyToSend(screen, "", "")) return true
            if (!isFilePickerVisible(runner)) return true
            delay(250)
        }
        return !isFilePickerVisible(runner)
    }

    private fun isFilePickerVisible(runner: SandboxedRunner): Boolean {
        val pkg = runner.getCurrentPackage().lowercase()
        if (pkg.contains("documentsui") ||
            pkg.contains("files") ||
            pkg.contains("filemanager") ||
            pkg.contains("fileexplorer")
        ) {
            return true
        }

        val screen = runner.readScreen().lowercase()
        val pickerWords = listOf("recent", "recents", "downloads", "browse", "open from", "documents")
        return pickerWords.count { screen.contains(it) } >= 2
    }

    private fun looksLikeFileReadyToSend(
        screen: String,
        fileQuery: String,
        caption: String,
    ): Boolean {
        val lower = screen.lowercase()
        if (lower.contains("add a caption")) return true
        if (lower.contains("caption") && lower.contains("send")) return true

        val fileMarkers = listOf(
            ".pdf",
            ".doc",
            ".docx",
            ".xls",
            ".xlsx",
            ".ppt",
            ".pptx",
            "document",
            "attachment",
            "selected",
        )
        if (fileMarkers.any { lower.contains(it) } && lower.contains("send")) return true

        if (fileQuery.isNotBlank()) {
            val tokens = fileQuery
                .split(Regex("[^A-Za-z0-9._-]+"))
                .filter { it.length >= 3 }
                .map { it.lowercase() }
            if (tokens.any { lower.contains(it) } && (lower.contains("send") || lower.contains("caption"))) {
                return true
            }
        }

        if (caption.isNotBlank()) {
            val verify = caption.take(8)
            if (verify.length >= 3 && lower.contains(verify.lowercase()) && lower.contains("send")) {
                return true
            }
        }

        return false
    }

    private suspend fun tapSendButton(runner: SandboxedRunner): Boolean {
        if (runner.tapByText("Send")) return true

        val sendEl = runner.getClickableElements()
            .asSequence()
            .filter { it.isClickable }
            .filter { el ->
                val combined = (el.text + " " + el.contentDescription).lowercase()
                combined.contains("send") &&
                    !combined.contains("audio") &&
                    !combined.contains("voice") &&
                    !combined.contains("record")
            }
            .maxByOrNull { it.centerY * 10 + it.centerX }
            ?: return false

        return runner.tapAtPoint(sendEl.centerX.toFloat(), sendEl.centerY.toFloat())
    }

    private suspend fun tapWhatsAppSearch(runner: SandboxedRunner): Boolean {
        val (screenW, screenH) = runner.getScreenSize()
        val topZone = screenH * 0.12f

        val searchEl = runner.getClickableElements().firstOrNull { el ->
            val combined = (el.text + el.hint + el.contentDescription + el.viewId).lowercase()
            (combined.contains("search") || combined.contains("find")) &&
                el.centerY < topZone &&
                el.width < screenW * 0.25f
        }
        if (searchEl != null) {
            return runner.tapAtPoint(searchEl.centerX.toFloat(), searchEl.centerY.toFloat())
        }

        if (runner.tapByText("Search")) return true

        return runner.tapAtPoint(screenW * 0.85f, screenH * 0.06f)
    }

    private fun buildMessageTypingFallbackGoal(contact: String, message: String): String {
        return """
You are inside the WhatsApp chat for "$contact".
Tap the editable message field at the bottom of the screen.
Type this exact text into that field:
$message
Do not press Send.
Stop only when the typed message is visible in the composer.
        """.trimIndent()
    }

    private fun buildContactFallbackGoal(contact: String): String {
        return """
You are in WhatsApp search results for "$contact".
Tap the contact row that matches "$contact".
Do not tap the small avatar on the far left.
Stop only when the chat is open and the bottom message field says "Type a message" or "Message".
        """.trimIndent()
    }

    private fun buildTapFileInstruction(
        fileQuery: String,
        folder: String,
    ): String {
        return buildString {
            append("Tap the file row in this Android file picker that best matches ")
            append("\"$fileQuery\"")
            if (folder.isNotBlank()) {
                append(" from the $folder location")
            }
            append(". Prefer a document or PDF file, not a folder or category.")
        }
    }

    private fun buildFileConfirmationPrompt(
        contact: String,
        targetFile: String,
        caption: String,
    ): String {
        val captionLine = if (caption.isBlank()) "" else "\nCaption: \"$caption\""
        return "File ready for *$contact* on WhatsApp:\n$targetFile$captionLine\n\nReply *YES* to send it, or *NO* to cancel."
    }

    private fun describeTargetFile(fileQuery: String, folder: String): String {
        return when {
            fileQuery.isNotBlank() && folder.isNotBlank() -> "\"$fileQuery\" from $folder"
            fileQuery.isNotBlank() -> "\"$fileQuery\""
            folder.isNotBlank() -> "the selected file from $folder"
            else -> "the selected document"
        }
    }

    private fun firstNonBlank(vararg values: String?): String {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
    }

    private companion object {
        val messageActions = setOf("send", "message")
        val fileActions = setOf(
            "send_file",
            "send_files",
            "send_document",
            "send_doc",
            "send_pdf",
            "share_file",
            "share_document",
            "share_pdf",
            "attach_file",
            "document",
            "file",
        )
    }
}
