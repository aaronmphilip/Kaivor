package com.kaivor.agent

import android.content.Context

/**
 * SavedPlacesStore — remembers named locations so the user never types addresses again.
 *
 * Usage:
 *   /place save home Koramangala, Bangalore
 *   /place save work Whitefield, Bangalore
 *   /place save gym Gold's Gym, Indiranagar
 *   /place list
 *   /place delete gym
 *
 * Skills can then say "take me home" and the orchestrator resolves "home" → full address.
 */
class SavedPlacesStore(context: Context) {

    private val prefs = context.getSharedPreferences("kaivor_places", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PLACES = "saved_places"
        private const val SEPARATOR = "|||"
        private const val KV_SEPARATOR = ":::"

        // Common place name aliases so "take me home" / "go home" / "navigate home" all work
        val PLACE_TRIGGERS = listOf(
            "home", "work", "office", "gym", "school", "college", "hospital",
            "airport", "station", "mall", "temple", "mosque", "church",
            "घर", "दफ्तर", "दफ़्तर",  // Hindi
        )
    }

    /** Save a named place. Overwrites if the name already exists. */
    fun save(name: String, address: String): Boolean {
        val cleanName = name.trim().lowercase()
        val cleanAddress = address.trim()
        if (cleanName.isBlank() || cleanAddress.isBlank()) return false
        val all = loadAll().toMutableMap()
        all[cleanName] = cleanAddress
        saveAll(all)
        return true
    }

    /** Get a saved place address by name. Returns null if not found. */
    fun get(name: String): String? {
        val cleanName = name.trim().lowercase()
        return loadAll()[cleanName]
    }

    /** Delete a saved place. Returns true if it existed. */
    fun delete(name: String): Boolean {
        val cleanName = name.trim().lowercase()
        val all = loadAll().toMutableMap()
        val existed = all.remove(cleanName) != null
        if (existed) saveAll(all)
        return existed
    }

    /** List all saved places as a map. */
    fun listAll(): Map<String, String> = loadAll()

    /** Clear all saved places. */
    fun clearAll() {
        prefs.edit().remove(KEY_PLACES).apply()
    }

    /**
     * Try to expand a place name in a command string.
     * e.g. "take me home" → "take me to Koramangala, Bangalore"
     *      "navigate to work" → "navigate to Whitefield, Bangalore"
     * Returns the expanded string, or the original if no place name was found.
     */
    fun expandInCommand(command: String): String {
        val all = loadAll()
        if (all.isEmpty()) return command
        var result = command
        for ((name, address) in all) {
            // Replace " home", "to home", "at home" etc. but not "homework"
            val pattern = Regex("""(?<![a-z])${Regex.escape(name)}(?![a-z])""", RegexOption.IGNORE_CASE)
            if (pattern.containsMatchIn(result)) {
                result = pattern.replace(result) { "\"$address\"" }
            }
        }
        return result
    }

    /**
     * Build a Telegram message listing all saved places.
     */
    fun buildListMessage(): String {
        val all = loadAll()
        if (all.isEmpty()) {
            return "📍 No saved places yet.\n\nSave one:\n`/place save home Koramangala, Bangalore`\n`/place save work MG Road, Bangalore`"
        }
        return buildString {
            appendLine("📍 *Saved Places*")
            appendLine()
            all.entries.sortedBy { it.key }.forEach { (name, address) ->
                appendLine("• *$name*: $address")
            }
            appendLine()
            appendLine("Use these in commands: _\"take me home\"_, _\"navigate to work\"_, _\"Ola to gym\"_")
            appendLine()
            appendLine("Commands: `/place save <name> <address>` · `/place delete <name>` · `/place list`")
        }.trim()
    }

    /**
     * Handle a /place command string.
     * Examples:
     *   "save home Koramangala" → saves
     *   "delete gym" → deletes
     *   "list" or "" → shows list
     *   "clear" → clears all
     */
    fun handleCommand(args: String): String {
        val trimmed = args.trim()
        val lower = trimmed.lowercase()

        if (trimmed.isBlank() || lower == "list" || lower == "show") {
            return buildListMessage()
        }

        if (lower == "clear") {
            val count = loadAll().size
            clearAll()
            return "🧹 Cleared all $count saved place${if (count != 1) "s" else ""}."
        }

        if (lower.startsWith("delete ") || lower.startsWith("remove ")) {
            val name = trimmed.substringAfter(" ").trim()
            return if (delete(name)) {
                "✅ Deleted place: *$name*"
            } else {
                "Place \"$name\" not found. Use `/place list` to see saved places."
            }
        }

        if (lower.startsWith("save ") || lower.startsWith("add ")) {
            val rest = trimmed.substringAfter(" ").trim()
            // Format: "save <name> <address>" — name is first word, address is the rest
            val parts = rest.split(" ", limit = 2)
            if (parts.size < 2 || parts[1].isBlank()) {
                return "Usage: `/place save <name> <address>`\nExample: `/place save home Koramangala 5th Block, Bangalore`"
            }
            val name = parts[0].lowercase()
            val address = parts[1].trim()
            save(name, address)
            return "📍 Saved: *$name* → $address\n\nNow you can say: _\"take me $name\"_ or _\"Ola to $name\"_"
        }

        // Try interpreting as just a name lookup
        val address = get(trimmed)
        if (address != null) {
            return "📍 *$trimmed* → $address"
        }

        return "Unknown place command. Try:\n`/place list` · `/place save home <address>` · `/place delete home`"
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun loadAll(): Map<String, String> {
        val raw = prefs.getString(KEY_PLACES, "") ?: return emptyMap()
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
        prefs.edit().putString(KEY_PLACES, serialized).apply()
    }
}
