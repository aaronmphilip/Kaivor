package com.bharatdroid.agent.skills.builtin

import android.graphics.Bitmap
import com.bharatdroid.agent.DocumentSummaryBrain
import com.bharatdroid.agent.skills.DeliveryMode
import com.bharatdroid.agent.skills.Permission
import com.bharatdroid.agent.skills.Skill
import com.bharatdroid.agent.skills.SkillContext
import com.bharatdroid.agent.skills.SkillManifest
import com.bharatdroid.agent.skills.SkillResult
import kotlinx.coroutines.delay

class ReadingConciergeSkill : Skill {

    override val manifest = SkillManifest(
        id = "reading_concierge",
        name = "Reading Concierge",
        version = "1.0.0",
        description = "Open WhatsApp documents or article pages, read through them by scrolling, summarize them, and send the final summary back through Telegram. Use for PDFs in WhatsApp, links/articles, and long reading tasks.",
        author = "bharatdroid-team",
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
        allowedPackages = emptySet(),
        exampleParamsHint = """{"action":"whatsapp_pdf","contact":"Me","instruction":"one page summary with action items"}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")
        val documentBrain = DocumentSummaryBrain.fromContext(runner.getContext())
            ?: return SkillResult.Failure("No AI model is configured yet. Add your key in Settings first.")

        val action = (params["action"] as? String)?.lowercase() ?: "current"
        val instruction = (params["instruction"] as? String)
            ?: (params["summary"] as? String)
            ?: (params["goal"] as? String)
            ?: "Give me a concise summary with the key points, action items, dates, and important numbers."
        val contact = params["contact"] as? String ?: ""
        val query = params["query"] as? String ?: params["title"] as? String ?: params["search"] as? String ?: ""
        val url = params["url"] as? String ?: ""
        val scrolls = ((params["scrolls"] as? Number)?.toInt() ?: 5).coerceIn(1, 8)

        val documentName = buildDocumentName(action, contact, query, url)

        when (action) {
            "whatsapp_pdf", "whatsapp_document", "whatsapp_doc", "whatsapp_file", "latest_whatsapp_pdf" -> {
                prepareApp(runner, "com.whatsapp")
                val goal = buildWhatsAppDocumentGoal(contact, query)
                agent.executeGoal(runner, goal, maxSteps = 50)
            }

            "whatsapp_article", "whatsapp_link", "latest_whatsapp_article" -> {
                prepareApp(runner, "com.whatsapp")
                val goal = buildWhatsAppArticleGoal(contact, query)
                agent.executeGoal(runner, goal, maxSteps = 50)
            }

            "article", "web_article", "chrome_article", "read_article", "summarize_article" -> {
                prepareApp(runner, "com.android.chrome")
                val goal = buildArticleGoal(query, url)
                agent.executeGoal(runner, goal, maxSteps = 55)
            }

            "current", "screen", "visible", "open_document" -> {
                // Already on the right screen — just summarize it below.
            }

            else -> {
                val goal = (params["goal"] as? String)?.takeIf { it.isNotBlank() }
                    ?: "Open the relevant document or article, stop when the readable content is visible, and do not send any message."
                agent.executeGoal(runner, goal, maxSteps = 55)
            }
        }

        if (action.startsWith("whatsapp")) {
            val screen = runner.readScreen()
            if (screen.contains("Type a message", ignoreCase = true) ||
                screen.contains("Search", ignoreCase = true) && screen.contains("WhatsApp", ignoreCase = true)
            ) {
                return SkillResult.Failure("I reached WhatsApp, but I couldn't get the document/article open yet.")
            }
        }

        val summary = summarizeReadingSession(
            runner = runner,
            documentBrain = documentBrain,
            documentName = documentName,
            instruction = instruction,
            scrolls = scrolls,
        )

        return SkillResult.Success(summary, delivery = DeliveryMode.LONG_TEXT)
    }

    private suspend fun prepareApp(
        runner: com.bharatdroid.agent.skills.SandboxedRunner,
        packageName: String,
    ) {
        runner.openApp(packageName)
        runner.waitForApp(packageName, timeoutMs = 6000)
        delay(700)
        runner.dismissPopups(2)
        delay(300)
    }

    private fun buildWhatsAppDocumentGoal(contact: String, query: String): String {
        return buildString {
            append("You are in WhatsApp. ")
            if (contact.isNotBlank()) {
                append("Use WhatsApp search to find the chat for \"$contact\" and open it. ")
            } else {
                append("Open the most recent relevant chat. ")
            }
            if (query.isNotBlank()) {
                append("Find the most recent PDF or document related to \"$query\". ")
            } else {
                append("Find the most recent PDF or document message in that chat. ")
            }
            append("If the file is not downloaded, tap the download arrow/button and wait for it to finish. ")
            append("Open the PDF or document. If WhatsApp asks which app to use, choose the best PDF/document viewer. ")
            append("Stop only when the document content itself is visible and ready to read. ")
            append("Do not type or send any message. Do not share the file.")
        }
    }

    private fun buildWhatsAppArticleGoal(contact: String, query: String): String {
        return buildString {
            append("You are in WhatsApp. ")
            if (contact.isNotBlank()) {
                append("Use search to open the chat for \"$contact\". ")
            } else {
                append("Open the most recent relevant chat. ")
            }
            if (query.isNotBlank()) {
                append("Find the most recent article link or webpage message related to \"$query\". ")
            } else {
                append("Find the most recent article link or webpage message in the chat. ")
            }
            append("Open that link, allow Chrome or the browser/custom tab to load, and stop only when the article headline and body text are visible and ready to read. ")
            append("Do not type or send any message.")
        }
    }

    private fun buildArticleGoal(query: String, url: String): String {
        return if (url.isNotBlank()) {
            """
You are in Chrome. Open "$url".
Wait for the page to load fully.
If there is a cookie banner or reader prompt, dismiss it safely.
Stop only when the article headline and main body text are visible and ready to read.
            """.trimIndent()
        } else if (query.isNotBlank()) {
            """
You are in Chrome. Search for "$query".
Open the most relevant article or page.
If multiple results appear, choose the clearest article or official page.
Stop only when the article headline and main body text are visible and ready to read.
            """.trimIndent()
        } else {
            """
You are in Chrome.
If an article or webpage is already open, stay on it.
Otherwise open the most relevant article already visible from the current page.
Stop only when the article headline and main body text are visible and ready to read.
            """.trimIndent()
        }
    }

    private suspend fun summarizeReadingSession(
        runner: com.bharatdroid.agent.skills.SandboxedRunner,
        documentBrain: DocumentSummaryBrain,
        documentName: String,
        instruction: String,
        scrolls: Int,
    ): String {
        val visibleTexts = mutableListOf<String>()
        val screenshots = mutableListOf<Bitmap>()
        val seenFingerprints = linkedSetOf<String>()

        for (index in 0..scrolls) {
            runner.captureScreenshot()?.let { screenshots += it }

            val rawText = runner.readScreen().trim()
            val normalizedText = normalizePageText(rawText)
            val fingerprint = normalizedText.take(700)
            if (fingerprint.isNotBlank() && seenFingerprints.add(fingerprint)) {
                visibleTexts += "Screen ${index + 1}:\n$normalizedText"
            }

            if (index == scrolls) break

            val moved = if (runner.scrollDown()) true else runner.swipeUp()
            if (!moved) break
            delay(900)
        }

        val combinedText = visibleTexts.joinToString("\n\n---\n\n")
        return when {
            combinedText.length >= 220 -> documentBrain.summarizeVisibleText(
                documentName = documentName,
                visibleText = combinedText,
                instruction = instruction,
            )

            screenshots.isNotEmpty() -> documentBrain.summarizeVisibleScreens(
                documentName = documentName,
                screenshots = screenshots,
                instruction = instruction,
                coverageNote = "This summary is based on ${screenshots.size} captured phone screens while scrolling through the content.",
            )

            else -> "I opened $documentName, but I couldn't read enough on-screen content to summarize it yet."
        }
    }

    private fun buildDocumentName(
        action: String,
        contact: String,
        query: String,
        url: String,
    ): String {
        return when (action) {
            "whatsapp_pdf", "whatsapp_document", "whatsapp_doc", "whatsapp_file", "latest_whatsapp_pdf" -> {
                val from = contact.ifBlank { "WhatsApp" }
                if (query.isNotBlank()) "$query document from $from" else "WhatsApp document from $from"
            }

            "whatsapp_article", "whatsapp_link", "latest_whatsapp_article" -> {
                val from = contact.ifBlank { "WhatsApp" }
                if (query.isNotBlank()) "$query article from $from" else "WhatsApp article from $from"
            }

            "article", "web_article", "chrome_article", "read_article", "summarize_article" -> {
                when {
                    query.isNotBlank() -> query
                    url.isNotBlank() -> url
                    else -> "article in Chrome"
                }
            }

            else -> "document or article on screen"
        }
    }

    private fun normalizePageText(text: String): String {
        return text
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
