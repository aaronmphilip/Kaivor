package com.bharatdroid.agent.skills

import com.bharatdroid.agent.ScreenAgent

enum class Permission {
    READ_SCREEN,
    TAP,
    TYPE,
    SCROLL,
    OPEN_APP,
    NAVIGATE_BACK,
    CLIPBOARD,
    NOTIFICATIONS,
    SCREENSHOT,
    PAYMENT,         // HIGH-TRUST: confirm every execution
    SENSITIVE_READ,  // HIGH-TRUST: confirm every execution
}

data class SkillManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    val trusted: Boolean = false,
    val permissions: Set<Permission>,
    val allowedPackages: Set<String> = emptySet(),
    // Human-readable example params shown to the AI in its system prompt
    val exampleParamsHint: String = "{}",
    val uiKnowledge: String = "",  // Per-skill UI knowledge injected into every goal
)

interface Skill {
    val manifest: SkillManifest
    suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult
}

data class SkillContext(
    val runner: SandboxedRunner,
    val chatId: Long,
    val userId: String,
    /** AI-driven screen agent for smart action decisions. Null for community skills. */
    val agent: ScreenAgent? = null,
)

enum class DeliveryMode {
    SCREENSHOT_OR_TEXT,
    LONG_TEXT,
}

sealed class SkillResult {
    data class Success(
        val message: String,
        val delivery: DeliveryMode = DeliveryMode.SCREENSHOT_OR_TEXT,
    ) : SkillResult()
    data class Failure(val reason: String) : SkillResult()
    data class NeedsConfirmation(
        val prompt: String,
        val onConfirm: suspend () -> SkillResult,
        val onCancel: SkillResult = Failure("Cancelled by user."),
    ) : SkillResult()
}
