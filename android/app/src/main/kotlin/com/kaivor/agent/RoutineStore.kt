package com.kaivor.agent

import android.content.Context

/**
 * RoutineStore — named sequences of commands that run one after another.
 *
 * Usage:
 *   /routine add morning = give me my morning brief, order coffee from Swiggy, read my email
 *   /routine list
 *   /routine delete morning
 *   /routine morning    ← runs the routine
 */
class RoutineStore(context: Context) {

    private val prefs = context.getSharedPreferences("kaivor_routines", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ROUTINES = "routines"
        private const val SEPARATOR = "||||"
        private const val KV_SEP = ":::"
        private const val CMD_SEP = "~~~"
        private const val MAX_STEPS = 50
        private const val MAX_ROUTINES = 200
    }

    data class Routine(
        val name: String,
        val steps: List<String>,
    )

    sealed class RoutineCommand {
        data class Reply(val message: String) : RoutineCommand()
        data class Run(val routine: Routine) : RoutineCommand()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Save a named routine. Steps are comma-separated.
     * Enforces MAX_STEPS and MAX_ROUTINES limits.
     */
    fun save(name: String, stepsRaw: String): String {
        val cleanName = name.trim().lowercase()
        if (cleanName.isBlank()) return "❌ Routine name can't be blank."
        if (cleanName.any { it == ':' || it == '|' || it == '^' || it == '~' }) {
            return "❌ Routine name can't contain special characters."
        }

        val steps = stepsRaw.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (steps.isEmpty()) return "❌ No commands found. Separate steps with commas.\nExample: `/routine add morning = brief, order coffee, read email`"
        if (steps.size > MAX_STEPS) return "❌ Too many steps (max $MAX_STEPS). You specified ${steps.size}."

        val all = loadAll().toMutableMap()
        if (!all.containsKey(cleanName) && all.size >= MAX_ROUTINES) {
            return "❌ Max $MAX_ROUTINES routines reached. Delete one first with `/routine delete <name>`."
        }
        all[cleanName] = steps
        saveAll(all)

        return buildString {
            appendLine("✅ *Routine saved:* $cleanName")
            appendLine()
            steps.forEachIndexed { i, step -> appendLine("${i + 1}. $step") }
            appendLine()
            append("Run it with: `/routine $cleanName`")
        }.trim()
    }

    /** Get a routine by name. Returns null if not found. */
    fun get(name: String): Routine? {
        val cleanName = name.trim().lowercase()
        val steps = loadAll()[cleanName] ?: return null
        return Routine(cleanName, steps)
    }

    /** Delete a routine by name. Returns true if it existed. */
    fun delete(name: String): Boolean {
        val cleanName = name.trim().lowercase()
        val all = loadAll().toMutableMap()
        val existed = all.remove(cleanName) != null
        if (existed) saveAll(all)
        return existed
    }

    /**
     * Build a Telegram-formatted list of all routines.
     */
    fun buildListMessage(): String {
        val all = loadAll()
        if (all.isEmpty()) {
            return "🔗 No routines saved.\n\nCreate one:\n`/routine add morning = brief, order coffee, read email`\n\nThen run it with:\n`/routine morning`"
        }
        return buildString {
            appendLine("🔗 *Saved Routines (${all.size}):*")
            appendLine()
            all.entries.sortedBy { it.key }.forEach { (name, steps) ->
                appendLine("*$name* (${steps.size} step${if (steps.size != 1) "s" else ""}):")
                steps.forEachIndexed { i, step -> appendLine("  ${i + 1}. ${step.take(60)}") }
                appendLine()
            }
            append("Run: `/routine <name>` · Delete: `/routine delete <name>` · Add: `/routine add <name> = cmd1, cmd2`")
        }.trim()
    }

    /**
     * Handle a /routine command string.
     * Routes "add/save name = cmd1, cmd2", "delete name", "list", or treats arg as routine name to run.
     */
    fun handleCommand(args: String): RoutineCommand {
        val trimmed = args.trim()
        val lower = trimmed.lowercase()

        if (trimmed.isBlank() || lower == "list" || lower == "show") {
            return RoutineCommand.Reply(buildListMessage())
        }

        if (lower.startsWith("delete ") || lower.startsWith("remove ")) {
            val name = trimmed.substringAfter(" ").trim()
            return if (delete(name)) {
                RoutineCommand.Reply("✅ Deleted routine: *$name*")
            } else {
                RoutineCommand.Reply("Routine \"$name\" not found. Use `/routine list` to see saved routines.")
            }
        }

        if (lower.startsWith("add ") || lower.startsWith("save ")) {
            val rest = trimmed.substringAfter(" ").trim()
            val eqIdx = rest.indexOf("=")
            if (eqIdx == -1) {
                return RoutineCommand.Reply("Usage: `/routine add <name> = cmd1, cmd2, cmd3`\nExample: `/routine add morning = brief, order coffee, read email`")
            }
            val name = rest.substring(0, eqIdx).trim()
            val stepsRaw = rest.substring(eqIdx + 1).trim()
            return RoutineCommand.Reply(save(name, stepsRaw))
        }

        // Treat as routine name to run
        val routine = get(trimmed)
        return if (routine != null) {
            RoutineCommand.Run(routine)
        } else {
            RoutineCommand.Reply("Routine \"$trimmed\" not found. Use `/routine list` to see saved routines.\n\nCreate one:\n`/routine add $trimmed = cmd1, cmd2`")
        }
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun loadAll(): Map<String, List<String>> {
        val raw = prefs.getString(KEY_ROUTINES, "") ?: return emptyMap()
        if (raw.isBlank()) return emptyMap()
        return raw.split(SEPARATOR)
            .filter { it.contains(KV_SEP) }
            .associate { entry ->
                val kv = entry.split(KV_SEP, limit = 2)
                val name = kv[0]
                val steps = kv[1].split(CMD_SEP).filter { it.isNotBlank() }
                name to steps
            }
    }

    private fun saveAll(map: Map<String, List<String>>) {
        val serialized = map.entries.joinToString(SEPARATOR) { (name, steps) ->
            "$name$KV_SEP${steps.joinToString(CMD_SEP)}"
        }
        prefs.edit().putString(KEY_ROUTINES, serialized).apply()
    }
}
