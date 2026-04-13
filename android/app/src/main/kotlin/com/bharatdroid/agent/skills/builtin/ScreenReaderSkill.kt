package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class ScreenReaderSkill : Skill {

    override val manifest = SkillManifest(
        id = "screen",
        name = "Screen Reader & Analyzer",
        version = "3.0.0",
        description = "Read current screen content, scroll and read full pages, find text, take a screenshot. Use for reading documents, invoices, emails, or any visible content.",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.READ_SCREEN,
            Permission.TAP,
            Permission.SCROLL,
            Permission.NAVIGATE_BACK,
        ),
        allowedPackages = emptySet(),
        exampleParamsHint = """{"action": "read", "question": "what are the totals?"}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val action = (params["action"] as? String)?.lowercase() ?: "read"
        val question = params["question"] as? String
        val target = params["target"] as? String ?: params["text"] as? String ?: ""

        return when (action) {
            "read", "describe" -> {
                val screen = runner.readScreen()
                if (screen.isBlank()) return SkillResult.Failure("Screen appears empty or unreadable.")
                val header = if (question != null) "Q: $question\n\n" else ""
                SkillResult.Success("${header}📱 Screen content:\n```\n${screen.take(1200)}\n```")
            }

            "scroll_and_read", "full_read" -> {
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
                val text = runner.readScreen()
                SkillResult.Success(text.take(800))
            }
        }
    }
}
