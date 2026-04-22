package com.bharatdroid.agent

import android.content.Context
import com.bharatdroid.agent.skills.*

class SkillRunner(
    private val context: Context,
    private val permissionsStore: PermissionsStore,
    private val requestConfirmation: suspend (chatId: Long, question: String) -> Boolean,
    private val notifyUser: suspend (chatId: Long, message: String) -> Unit,
    private val screenAgent: ScreenAgent? = null,
) {
    private val registry = mutableMapOf<String, Skill>()

    fun register(skill: Skill) {
        registry[skill.manifest.id] = skill
    }

    fun listSkills(): List<SkillManifest> = registry.values.map { it.manifest }

    fun getSkillInfoForAI(): List<AIBrain.SkillInfo> = registry.values.map { skill ->
        AIBrain.SkillInfo(
            id = skill.manifest.id,
            description = skill.manifest.description,
            exampleParams = skill.manifest.exampleParams,
        )
    }

    suspend fun execute(
        skillId: String,
        params: Map<String, Any>,
        chatId: Long,
        userId: String,
    ): SkillResult {
        val skill = registry[skillId]
            ?: return SkillResult.Failure("Skill '$skillId' not found. Available: ${registry.keys.joinToString()}")

        val service = AgentAccessibilityService.instance
            ?: return SkillResult.Failure(
                "Accessibility service is not running. " +
                "Open BharatDroid app and enable it in Settings."
            )

        // ── Safety Gate 1: Community skill warning ──
        if (!skill.manifest.trusted && permissionsStore.shouldAsk("community")) {
            val allowed = requestConfirmation(
                chatId,
                "*Community Skill — Not Officially Verified*\n\n" +
                "*Name:* ${skill.manifest.name}\n" +
                "*Author:* ${skill.manifest.author}\n" +
                "*Permissions:* ${skill.manifest.permissions.joinToString(", ")}\n\n" +
                "Reply *YES* to allow or anything else to cancel."
            )
            if (!allowed) return SkillResult.Failure("Skill blocked — community skills require your approval.")
        }

        // ── Safety Gate 2: SENSITIVE_READ confirmation (ASK mode only) ──
        if (Permission.SENSITIVE_READ in skill.manifest.permissions && permissionsStore.mode == PermissionsStore.Mode.ASK) {
            val allowed = requestConfirmation(
                chatId,
                "⚠️ This skill will read sensitive screen content.\nSkill: *${skill.manifest.name}*\n\nReply *YES* to allow."
            )
            if (!allowed) return SkillResult.Failure("Sensitive read denied by user.")
        }

        // ── Execute ──
        val sandboxedRunner = SandboxedRunner(skill.manifest, service, context)
        val skillContext = SkillContext(
            runner = sandboxedRunner,
            chatId = chatId,
            userId = userId,
            agent = if (skill.manifest.trusted) screenAgent else null, // Only trusted skills get AI agent
        )

        return try {
            val result = skill.execute(skillContext, params)

            // Check for password screen after execution
            val passwordScreen = sandboxedRunner.detectPasswordScreen()
            if (passwordScreen != null) {
                val screen = sandboxedRunner.readScreen()
                notifyUser(chatId,
                    "A *password/PIN screen* appeared: *$passwordScreen*\n\n" +
                    "Screen:\n```\n${screen.take(300)}\n```\n\n" +
                    "Enter the PIN on your phone, then reply *CONTINUE* to proceed."
                )
                val continued = requestConfirmation(chatId, "")
                if (!continued) return SkillResult.Failure("User cancelled at password screen.")
            }

            // Handle NeedsConfirmation
            if (result is SkillResult.NeedsConfirmation) {
                if (permissionsStore.mode == PermissionsStore.Mode.AUTO) {
                    result.onConfirm()
                } else {
                    val confirmed = requestConfirmation(chatId, result.prompt)
                    if (confirmed) result.onConfirm() else result.onCancel
                }
            } else {
                result
            }
        } catch (e: IllegalStateException) {
            SkillResult.Failure(e.message ?: "App not available.")
        } catch (e: SecurityException) {
            SkillResult.Failure("Permission error: ${e.message}")
        } catch (e: Exception) {
            SkillResult.Failure("Skill error: ${e.message}")
        }
    }
}

val SkillManifest.exampleParams: String get() = exampleParamsHint
