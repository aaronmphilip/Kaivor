package com.kaivor.agent.skills

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

// ─────────────────────────────────────────────
// SKILL STORE
//
// Manages community skills installed by the user.
// Community skills are stored as JSON manifests in
// app storage. They use a pre-defined DSL (not
// arbitrary code) — interpreted by SkillInterpreter.
//
// This is what lets the community add skills WITHOUT
// the user rebuilding the APK. Install a skill by
// sending "/install <url>" to the bot.
// ─────────────────────────────────────────────

data class RemoteSkillManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    val permissions: List<String>,
    val allowedPackages: List<String>,
    val exampleParamsHint: String,
    // DSL steps — interpreted, not arbitrary code
    val steps: List<SkillStep>,
)

// ─────────────────────────────────────────────
// SKILL DSL
//
// Skills define a list of Steps. Steps use a
// constrained set of verbs — no arbitrary code.
// This is the safety guarantee for remote skills.
// ─────────────────────────────────────────────

data class SkillStep(
    val action: String,           // open_app, tap_text, type_in, wait_for, scroll, read, confirm, done
    val value: String? = null,    // primary argument
    val field: String? = null,    // field hint for type_in
    val param: String? = null,    // references a runtime param by name, e.g. "query"
    val timeoutMs: Long? = null,
    val message: String? = null,  // for confirm / done steps
)

// ─────────────────────────────────────────────
// SKILL INTERPRETER — runs DSL steps
// ─────────────────────────────────────────────

class SkillInterpreter(
    private val manifest: RemoteSkillManifest,
    private val sandboxed: SandboxedRunner,
) {
    suspend fun run(params: Map<String, Any>): SkillResult {
        for (step in manifest.steps) {
            val resolved = resolveValue(step, params)
            val result = executeStep(step, resolved, params)
            if (result != null) return result
        }
        return SkillResult.Success("Done.")
    }

    private fun resolveValue(step: SkillStep, params: Map<String, Any>): String {
        return when {
            step.param != null -> params[step.param]?.toString() ?: ""
            step.value != null -> step.value
            else -> ""
        }
    }

    private suspend fun executeStep(
        step: SkillStep,
        value: String,
        params: Map<String, Any>,
    ): SkillResult? {
        val declared = manifest.permissions.map { Permission.valueOf(it) }.toSet()

        when (step.action) {
            "open_app" -> {
                require(Permission.OPEN_APP in declared) { "OPEN_APP not declared" }
                sandboxed.openApp(value)
                sandboxed.waitForApp(value, step.timeoutMs ?: 6000)
            }
            "tap_text" -> {
                require(Permission.TAP in declared) { "TAP not declared" }
                sandboxed.tapByText(value)
            }
            "type_in" -> {
                require(Permission.TYPE in declared) { "TYPE not declared" }
                if (step.field != null) sandboxed.typeInFieldWithHint(step.field, value)
            }
            "wait_for" -> {
                require(Permission.READ_SCREEN in declared) { "READ_SCREEN not declared" }
                val found = sandboxed.waitForText(value, step.timeoutMs ?: 5000)
                if (!found) return SkillResult.Failure("Timed out waiting for: '$value'")
            }
            "scroll_down" -> {
                require(Permission.SCROLL in declared) { "SCROLL not declared" }
                sandboxed.scrollDown()
            }
            "scroll_up" -> {
                require(Permission.SCROLL in declared) { "SCROLL not declared" }
                sandboxed.scrollUp()
            }
            "back" -> {
                require(Permission.NAVIGATE_BACK in declared) { "NAVIGATE_BACK not declared" }
                sandboxed.pressBack()
            }
            "read" -> {
                require(Permission.READ_SCREEN in declared) { "READ_SCREEN not declared" }
                val screen = sandboxed.readScreen()
                return SkillResult.Success(
                    (step.message ?: "Here's what I see:") +
                    "\n```\n${screen.take(600)}\n```"
                )
            }
            "confirm" -> {
                val message = step.message ?: "Proceed? Reply YES to confirm."
                return SkillResult.NeedsConfirmation(
                    prompt = message,
                    onConfirm = { SkillResult.Success("Proceeding...") },
                )
            }
            "done" -> {
                return SkillResult.Success(step.message ?: "Done.")
            }
            else -> return SkillResult.Failure("Unknown DSL action: '${step.action}'")
        }
        return null // Continue to next step
    }
}

// ─────────────────────────────────────────────
// SKILL STORE — persistence + fetch
// ─────────────────────────────────────────────

class SkillStore(private val context: Context) {
    private val gson = Gson()
    private val client = OkHttpClient()
    private val skillsDir get() = File(context.filesDir, "community_skills").also { it.mkdirs() }

    // Install a skill from a URL (must be a raw JSON skill manifest)
    suspend fun install(url: String): Result<RemoteSkillManifest> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            val body = client.newCall(request).execute().use { it.body?.string() }
                ?: return@withContext Result.failure(Exception("Empty response from URL"))

            val manifest = gson.fromJson(body, RemoteSkillManifest::class.java)

            // Validate
            validateManifest(manifest)

            // Save to disk
            val file = File(skillsDir, "${manifest.id}.json")
            file.writeText(body)

            Result.success(manifest)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun loadAll(): List<RemoteSkillManifest> {
        return skillsDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                try { gson.fromJson(file.readText(), RemoteSkillManifest::class.java) }
                catch (e: Exception) { null }
            } ?: emptyList()
    }

    fun uninstall(skillId: String): Boolean {
        return File(skillsDir, "$skillId.json").delete()
    }

    fun listInstalled(): List<String> {
        return skillsDir.listFiles { f -> f.extension == "json" }
            ?.map { it.nameWithoutExtension } ?: emptyList()
    }

    private fun validateManifest(manifest: RemoteSkillManifest) {
        require(manifest.id.isNotBlank()) { "Skill id cannot be blank" }
        require(manifest.id.matches(Regex("[a-z0-9_-]+"))) {
            "Skill id must be lowercase alphanumeric/hyphens only"
        }
        require(manifest.name.isNotBlank()) { "Skill name cannot be blank" }
        require(manifest.author.isNotBlank()) { "Skill author cannot be blank" }
        require(manifest.steps.isNotEmpty()) { "Skill must have at least one step" }

        // Validate all permissions are known
        manifest.permissions.forEach { perm ->
            Permission.valueOf(perm) // Throws if unknown
        }

        // If OPEN_APP is declared, allowedPackages must be non-empty
        if ("OPEN_APP" in manifest.permissions) {
            require(manifest.allowedPackages.isNotEmpty()) {
                "Skill declares OPEN_APP but allowedPackages is empty"
            }
        }

        // DSL actions must be from the allowed set
        val allowedActions = setOf(
            "open_app", "tap_text", "type_in", "wait_for",
            "scroll_down", "scroll_up", "back", "read", "confirm", "done"
        )
        manifest.steps.forEach { step ->
            require(step.action in allowedActions) {
                "Unknown DSL action '${step.action}'. Allowed: ${allowedActions.joinToString()}"
            }
        }
    }
}

// ─────────────────────────────────────────────
// REMOTE SKILL ADAPTER — wraps RemoteSkillManifest
// as a Skill so SkillRunner can execute it
// ─────────────────────────────────────────────

class RemoteSkill(private val remote: RemoteSkillManifest) : Skill {

    override val manifest = SkillManifest(
        id = remote.id,
        name = remote.name,
        version = remote.version,
        description = remote.description,
        author = remote.author,
        trusted = false, // Community skills are never auto-trusted
        permissions = remote.permissions.map { Permission.valueOf(it) }.toSet(),
        allowedPackages = remote.allowedPackages.toSet(),
        exampleParamsHint = remote.exampleParamsHint,
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val interpreter = SkillInterpreter(remote, context.runner)
        return interpreter.run(params)
    }
}
