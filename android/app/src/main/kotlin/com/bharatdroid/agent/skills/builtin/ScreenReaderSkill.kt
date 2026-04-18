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
        author = "bharatdroid-team",
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
        val scrollPasses = ((params["scrolls"] as? Number)?.toInt() ?: 3).coerceIn(0, 6)

        return when (action) {
            "read", "describe" -> {
                if (wantsSummary(instruction)) {
                    summarizeVisibleContent(runner, instruction.orEmpty(), scrollPasses)
                } else {
                    val screen = runner.readScreen()
                    if (screen.isBlank()) return SkillResult.Failure("Screen appears empty or unreadable.")
                    val header = if (question != null) "Q: $question\n\n" else ""
                    SkillResult.Success("${header}📱 Screen content:\n```\n${screen.take(1200)}\n```")
                }
            }

            "summarize", "summary", "analyze", "analyze_document" -> {
                summarizeVisibleContent(runner, instruction.orEmpty(), scrollPasses)
            }

            "scroll_and_read", "full_read" -> {
                if (wantsSummary(instruction)) {
                    summarizeVisibleContent(runner, instruction.orEmpty(), scrollPasses)
                } else {
                    val sb = StringBuilder()
                    sb.appendLine(runner.readScreen())
                    repeat(3) { i ->
                        runner.scrollDown()
                        delay(700)
                        sb.appendLine("── scroll ${i + 2} ──")
                        sb.appendLine(runner.readScreen())
                    }
                    val header = if (question != null) "Q: $question\n\n" else ""
                    SkillResult.Success("${header}📱 Full content:\n```\n${sb.toString().take(2000)}\n```")
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

    private suspend fun captureVisibleDocument(
        runner: SandboxedRunner,
        scrollPasses: Int,
    ): String {
        val sb = StringBuilder()
        val firstScreen = runner.readScreen()
        if (firstScreen.isNotBlank()) sb.appendLine(firstScreen)

        for (index in 0 until scrollPasses) {
            if (!runner.scrollDown()) break
            delay(700)
            val nextScreen = runner.readScreen()
            if (nextScreen.isNotBlank()) {
                sb.appendLine("── scroll ${index + 2} ──")
                sb.appendLine(nextScreen)
            }
        }

        return sb.toString().trim()
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
