package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.Permission
import com.bharatdroid.agent.skills.Skill
import com.bharatdroid.agent.skills.SkillContext
import com.bharatdroid.agent.skills.SkillManifest
import com.bharatdroid.agent.skills.SkillResult
import kotlinx.coroutines.delay

class WhatsAppSkill : Skill {

    override val manifest = SkillManifest(
        id = "whatsapp",
        name = "WhatsApp Messaging",
        version = "8.1.0",
        description = "Send WhatsApp messages, read chats, and handle general WhatsApp tasks",
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
        allowedPackages = setOf("com.whatsapp"),
        exampleParamsHint = """{"action": "send", "contact": "Mom", "message": "Coming home for dinner"}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase() ?: "send"
        val contact = params["contact"] as? String ?: ""
        val message = params["message"] as? String ?: ""

        // CRITICAL: Require contact for send action (no defaults to Papa or others)
        if (action == "send" && contact.isBlank()) {
            return SkillResult.Failure("CONTACT NAME REQUIRED for message. Who should I message? (Specify exact contact name)")
        }

        runner.openApp("com.whatsapp")
        runner.waitForApp("com.whatsapp", timeoutMs = 5000)
        delay(350)
        runner.dismissPopups(1)
        delay(150)

        return when (action) {
            "read" -> {
                val screen = runner.readScreen()
                SkillResult.Success("Recent WhatsApp chats:\n```\n${screen.take(800)}\n```")
            }

            "send" -> {
                if (contact.isBlank()) return SkillResult.Failure("Who should I message?")
                if (message.isBlank()) return SkillResult.Failure("What should I say?")

                SkillResult.NeedsConfirmation(
                    prompt = "Send to *$contact*:\n\"$message\"\n\nReply *YES* to send.",
                    onConfirm = {
                        val goal = buildString {
                            append("TASK: Send WhatsApp message to $contact\n\n")
                            append("Contact: $contact\n")
                            append("Message: $message\n\n")
                            append("REQUIRED ACTIONS:\n")
                            append("1. Go to chat list (press back if a chat is open)\n")
                            append("2. Find the search icon and tap it\n")
                            append("3. Search for contact: $contact\n")
                            append("4. Tap the exact contact match from results\n")
                            append("5. Find the message input box at bottom\n")
                            append("6. Type the message: $message\n")
                            append("7. Find and tap the SEND button\n\n")
                            append("CRITICAL RULES:\n")
                            append("- Never tap random chats - use search\n")
                            append("- Contact name: $contact (exact match only)\n")
                            append("- Message text: $message\n")
                            append("- When complete, confirm message was sent\n")
                        }
                        val result = agent.executeGoal(runner, goal, maxSteps = 22)
                        SkillResult.Success("OK: $result")
                    },
                )
            }

            else -> {
                val goal = params["goal"] as? String
                    ?: "Do this in WhatsApp: $action ${contact.ifBlank { "" }} ${message.ifBlank { "" }}".trim()
                val result = agent.executeGoal(runner, goal, maxSteps = 20)
                SkillResult.Success(result)
            }
        }
    }
}
