package com.bharatdroid.agent

import android.content.Context
import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.CompletableDeferred

// ─────────────────────────────────────────────
// SKILL RUNNER
//
// Registry + safety gate for all skills.
// Enforces:
//   1. Community skill warning (first run)
//   2. PAYMENT permission = confirm every time
//   3. Permission sandboxing via SandboxedRunner
// ─────────────────────────────────────────────

class SkillRunner(
    private val context: Context,
    // Called when user confirmation is needed mid-execution.
    // Sends the question via Telegram and waits for YES/NO reply.
    private val requestConfirmation: suspend (chatId: Long, question: String) -> Boolean,
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
                "Open BharatDroid app → tap 'Enable Agent' → grant permission."
            )

        // ── Safety Gate 1: Community skill warning ──
        if (!skill.manifest.trusted) {
            val allowed = requestConfirmation(
                chatId,
                "⚠️ *Community Skill — Not Officially Verified*\n\n" +
                "*Name:* ${skill.manifest.name}\n" +
                "*Author:* ${skill.manifest.author}\n" +
                "*Permissions:* ${skill.manifest.permissions.joinToString(", ")}\n\n" +
                "Reply *YES* to allow or anything else to cancel."
            )
            if (!allowed) return SkillResult.Failure("Skill blocked — community skills require your approval.")
        }

        // ── Safety Gate 2: PAYMENT always needs explicit confirmation ──
        if (Permission.PAYMENT in skill.manifest.permissions) {
            val allowed = requestConfirmation(
                chatId,
                "💳 This action involves a *payment screen*.\n" +
                "Skill: *${skill.manifest.name}*\n\n" +
                "Reply *YES* to proceed or anything else to cancel."
            )
            if (!allowed) return SkillResult.Failure("Payment permission denied by user.")
        }

        // ── Execute with sandboxed runner ──
        val sandboxedRunner = SandboxedRunner(skill.manifest, service, context)
        val skillContext = SkillContext(sandboxedRunner, chatId, userId)

        return try {
            val result = skill.execute(skillContext, params)
            // If skill needs mid-execution confirmation, handle it
            if (result is SkillResult.NeedsConfirmation) {
                val confirmed = requestConfirmation(chatId, result.prompt)
                if (confirmed) result.onConfirm() else result.onCancel
            } else {
                result
            }
        } catch (e: SecurityException) {
            SkillResult.Failure("Skill permission error: ${e.message}")
        } catch (e: Exception) {
            SkillResult.Failure("Skill crashed: ${e.message}")
        }
    }
}

// Extension so SkillManifest can expose an example for the AI prompt
val SkillManifest.exampleParams: String get() = exampleParamsHint
