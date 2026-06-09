package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.DocumentSummaryBrain
import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class ScreenReaderSkill : Skill {

    override val manifest = SkillManifest(
        id = "screen",
        name = "Screen Reader & Analyzer",
        version = "3.1.0",
        description = "Read or summarize current screen content, scroll and read full pages, find text, and analyze visible documents, invoices, emails, or any on-screen content.",
        author = "bharatclaw-team",
        trusted = true,
        permissions = setOf(
            Permission.READ_SCREEN,
            Permission.TAP,
            Permission.SCROLL,
            Permission.NAVIGATE_BACK,
        ),
        allowedPackages = emptySet(),
        exampleParamsHint = """{"action": "summarize", "instruction": "one paragraph summary focused on action items"}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val action = (params["action"] as? String)?.lowercase() ?: "read"
        val question = params["question"] as? String
        val instruction = params["instruction"] as? String
            ?: params["summary"] as? String
            ?: question
        val target = params["target"] as? String ?: params["text"] as? String ?: ""
        val scrollPasses = ((params["scrolls"] as? Number)?.toInt() ?: 5).coerceIn(0, 50)

        return when (action) {
            "read", "describe" -> {
                if (wantsSummary(instruction)) {
                    summarizeVisibleContent(runner, instruction.orEmpty(), scrollPasses)
                } else {
                    val screen = runner.readScreen()
                    if (screen.isBlank()) return SkillResult.Failure("Screen appears empty or unreadable.")
                    val header = if (question != null) "Q: $question\n\n" else ""
                    SkillResult.Success("${header}-- Screen content:\n```\n${screen.take(1200)}\n```")
                }
            }

            "summarize", "summary", "analyze", "analyze_document" -> {
                summarizeVisibleContent(runner, instruction.orEmpty(), scrollPasses)
            }

            "scroll_and_read", "full_read", "read_all", "deep_read" -> {
                val maxScrolls = ((params["scrolls"] as? Number)?.toInt() ?: 20).coerceIn(1, 100)
                if (wantsSummary(instruction)) {
                    summarizeVisibleContent(runner, instruction.orEmpty(), maxScrolls)
                } else {
                    val content = captureFullDocumentDedup(runner, maxScrolls)
                    val header = if (question != null) "Q: $question\n\n" else ""
                    val preview = content.take(3500)
                    val suffix = if (content.length > 3500) "\n_...${content.length} chars total - ask me to summarize for a concise version._" else ""
                    SkillResult.Success(
                        "${header}-- Full content:\n```\n$preview\n```$suffix",
                        delivery = DeliveryMode.LONG_TEXT,
                    )
                }
            }

            "find" -> {
                if (target.isBlank()) return SkillResult.Failure("What should I look for on screen?")
                if (runner.screenContains(target)) return SkillResult.Success("Found '$target' on screen.")
                repeat(3) {
                    runner.scrollDown()
                    delay(600)
                    if (runner.screenContains(target)) return SkillResult.Success("Found '$target' on screen (after scrolling).")
                }
                SkillResult.Failure("Could not find '$target' on screen.")
            }

            "elements" -> {
                val elements = runner.getClickableElements()
                val list = elements.mapIndexed { i, el ->
                    "[$i] ${el.type} '${el.text.take(40)}' at (${el.centerX},${el.centerY})"
                }.joinToString("\n")
                SkillResult.Success("Clickable elements:\n$list")
            }

            "back" -> {
                runner.pressBack()
                delay(800)
                val screen = runner.readScreen()
                SkillResult.Success("Went back.\n```\n${screen.take(600)}\n```")
            }

            else -> {
                if (wantsSummary(instruction)) {
                    summarizeVisibleContent(runner, instruction.orEmpty(), scrollPasses)
                } else {
                    val text = runner.readScreen()
                    SkillResult.Success(text.take(800))
                }
            }
        }
    }

    private suspend fun summarizeVisibleContent(
        runner: SandboxedRunner,
        instruction: String,
        scrollPasses: Int,
    ): SkillResult {
        val content = captureVisibleDocument(runner, scrollPasses)
        if (content.isBlank()) return SkillResult.Failure("Screen appears empty or unreadable.")

        val brain = DocumentSummaryBrain.fromContext(runner.getContext())
            ?: return SkillResult.Failure("No AI model is configured yet. Add your key in Settings first.")
        val summary = brain.summarizeVisibleText(
            documentName = "current screen",
            visibleText = content,
            instruction = instruction.ifBlank {
                "Give me a concise summary with the key points, action items, dates, and important numbers."
            },
        )
        return SkillResult.Success(summary, delivery = DeliveryMode.LONG_TEXT)
    }

    /**
     * Captures visible text across multiple scroll passes.
     * Each pass reads the full screen - content is concatenated with scroll markers.
     * Used for AI summarization (AI handles de-dup in its context window).
     */
    private suspend fun captureVisibleDocument(
        runner: SandboxedRunner,
        scrollPasses: Int,
    ): String {
        val sb = StringBuilder()
        val firstScreen = runner.readScreen()
        if (firstScreen.isNotBlank()) sb.appendLine(firstScreen)

        for (index in 0 until scrollPasses) {
            if (!runner.scrollDown()) break
            delay(650)
            val nextScreen = runner.readScreen()
            if (nextScreen.isNotBlank()) {
                sb.appendLine("-- scroll ${index + 2} --")
                sb.appendLine(nextScreen)
            }
        }

        return sb.toString().trim()
    }

    /**
     * Deep document capture with deduplication - for full_read mode where we want
     * unique content only. Stops automatically when no new content appears (end of doc).
     * Uses line-level deduplication so repeated nav bars / headers are suppressed.
     */
    private suspend fun captureFullDocumentDedup(
        runner: SandboxedRunner,
        maxScrolls: Int,
    ): String {
        val seenLines = mutableSetOf<String>()
        val output = StringBuilder()

        fun addScreen(screen: String) {
            screen.lines().forEach { raw ->
                val line = raw.trim()
                // Skip blank lines and very short noise (single chars, decorators)
                if (line.length < 3) return@forEach
                // Case-insensitive dedup - navigation bars repeat on every screen
                if (seenLines.add(line.lowercase())) {
                    output.appendLine(line)
                }
            }
        }

        addScreen(runner.readScreen())

        var noNewCount = 0
        for (i in 0 until maxScrolls) {
            val prevLen = output.length
            if (!runner.scrollDown()) break
            delay(650)
            addScreen(runner.readScreen())
            if (output.length == prevLen) {
                noNewCount++
                if (noNewCount >= 2) break // end of document reached
            } else {
                noNewCount = 0
            }
        }

        return output.toString().trim()
    }

    private fun wantsSummary(instruction: String?): Boolean {
        if (instruction.isNullOrBlank()) return false
        val lower = instruction.lowercase()
        return listOf(
            "summary", "summarize", "summarise", "one paragraph", "one para",
            "1 paragraph", "1 para", "one page", "1 page", "two page",
            "2 page", "detailed", "key points", "brief"
        ).any { lower.contains(it) }
    }
}
