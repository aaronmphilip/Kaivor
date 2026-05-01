package com.bharatdroid.agent

import android.content.Context

/**
 * QuickMacrosStore — user-defined command shortcuts/aliases.
 *
 * Usage:
 *   /shortcut add morning = give me my morning brief
 *   /shortcut add pizza = order chicken pizza from Swiggy
 *   /shortcut add home = book an Ola to home
 *   /shortcut list
 *   /shortcut delete morning
 *
 * When the user types just "morning", the orchestrator looks it up and
 * runs "give me my morning brief" instead. Prefix matching also works:
 * if "pizza" → "order pizza from Swiggy", then "pizza extra cheese"
 * → "order pizza from Swiggy extra cheese".
 */
class QuickMacrosStore(context: Context) {

    private val prefs = context.getSharedPreferences("bharatdroid_macros", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_MACROS = "quick_macros"
        private const val SEPARATOR = "|||"
        private const val KV_SEPARATOR = ":::"
        private const val MAX_MACROS = 500
    }

    /** Save a macro alias. Overwrites if the name already exists. */
    fun save(name: String, command: String): Boolean {
        val cleanName = name.trim().lowercase()
        val cleanCommand = command.trim()
        if (cleanName.isBlank() || cleanCommand.isBlank() || cleanName.length > 40) return false
        val all = loadAll().toMutableMap()
        all[cleanName] = cleanCommand
        saveAll(all.entries.take(MAX_MACROS).associate { it.key to it.value })
        return true
    }

    /**
     * Try to resolve an input string as a saved macro.
     * Returns the expanded command if matched, null otherwise.
     * Tries exact match first, then prefix match.
     */
    fun resolve(input: String): String? {
        val lower = input.trim().lowercase()
        if (lower.isBlank()) return null
        val all = loadAll()
        all[lower]?.let { return it }
        for ((name, command) in all) {
            if (lower.startsWith("$name ")) {
                val extra = lower.substringAfter("$name ").trim()
                return "$command $extra"
            }
        }
        return null
    }

    /** Delete a macro. Returns true if it existed. */
    fun delete(name: String): Boolean {
        val cleanName = name.trim().lowercase()
        val all = loadAll().toMutableMap()
        val existed = all.remove(cleanName) != null
        if (existed) saveAll(all)
        return existed
    }

    /** List all macros. */
    fun listAll(): Map<String, String> = loadAll()

    /** Clear all macros. */
    fun clearAll() {
        prefs.edit().remove(KEY_MACROS).apply()
    }

    fun buildListMessage(): String {
        val all = loadAll()
        if (all.isEmpty()) {
            return "⚡ No shortcuts saved yet.\n\nCreate one:\n`/shortcut add morning = give me my morning brief`\n`/shortcut add pizza = order chicken pizza from Swiggy`"
        }
        return buildString {
            appendLine("⚡ *Quick Shortcuts*")
            appendLine()
            all.entries.sortedBy { it.key }.forEach { (name, command) ->
                appendLine("• *$name* → _${command.take(70)}${if (command.length > 70) "…" else ""}_")
            }
            appendLine()
            appendLine("Just type the shortcut name to run it instantly.")
            appendLine()
            appendLine("`/shortcut add <name> = <command>` · `/shortcut delete <name>` · `/shortcut list`")
        }.trim()
    }

    fun handleCommand(args: String): String {
        val trimmed = args.trim()
        val lower = trimmed.lowercase()

        if (trimmed.isBlank() || lower == "list" || lower == "show") return buildListMessage()

        if (lower == "clear") {
            val count = loadAll().size
            clearAll()
            return "🧹 Cleared all $count shortcut${if (count != 1) "s" else ""}."
        }

        if (lower.startsWith("delete ") || lower.startsWith("remove ")) {
            val name = trimmed.substringAfter(" ").trim().lowercase()
            return if (delete(name)) "✅ Deleted shortcut: *$name*"
            else "Shortcut \"$name\" not found. Use `/shortcut list` to see what's saved."
        }

        if (lower.startsWith("add ") || lower.startsWith("save ")) {
            val rest = trimmed.substringAfter(" ").trim()
            val eqIdx = rest.indexOf('=')
            if (eqIdx < 1) {
                return "Usage: `/shortcut add <name> = <command>`\nExample: `/shortcut add morning = give me my morning brief`"
            }
            val name = rest.substring(0, eqIdx).trim().lowercase()
            val command = rest.substring(eqIdx + 1).trim()
            if (name.isBlank() || command.isBlank()) {
                return "Both name and command must be non-empty."
            }
            if (name.length > 40) return "Shortcut name too long (max 40 chars)."
            save(name, command)
            return "⚡ Shortcut saved: *$name* → _${command}_\n\nNow just type _\"$name\"_ to run it instantly."
        }

        // Direct lookup
        val resolved = resolve(trimmed)
        if (resolved != null) return "⚡ *$trimmed* → _${resolved}_"

        return "Unknown /shortcut command.\n`/shortcut list` · `/shortcut add morning = ...` · `/shortcut delete morning`"
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun loadAll(): Map<String, String> {
        val raw = prefs.getString(KEY_MACROS, "") ?: return emptyMap()
        if (raw.isBlank()) return emptyMap()
        return raw.split(SEPARATOR)
            .filter { it.contains(KV_SEPARATOR) }
            .associate { entry ->
                val parts = entry.split(KV_SEPARATOR, limit = 2)
                parts[0] to parts[1]
            }
    }

    private fun saveAll(map: Map<String, String>) {
        val serialized = map.entries.joinToString(SEPARATOR) { "${it.key}$KV_SEPARATOR${it.value}" }
        prefs.edit().putString(KEY_MACROS, serialized).apply()
    }
}
