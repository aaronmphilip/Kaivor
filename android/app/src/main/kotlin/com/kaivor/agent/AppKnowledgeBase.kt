package com.kaivor.agent

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * AppKnowledgeBase — per-app progressive learning (AppAgent technique).
 *
 * How AppAgent works: after every successful interaction with an app, it generates
 * a short documentation entry: "To search on Zomato: tap the search icon at top-right,
 * then type in the field that appears." On the next task for the same app, those notes
 * are retrieved and injected into the AI prompt — so the agent doesn't re-explore
 * from scratch every time.
 *
 * Result: first task on a new app takes longer (exploring). Every task after that
 * is faster because the agent already knows the layout.
 *
 * All knowledge is stored on-device in SharedPreferences. Never sent anywhere.
 * Users can clear it with /knowledge clear.
 */
class AppKnowledgeBase(private val context: Context) {

    companion object {
        private const val PREFS = "kaivor_appknowledge"
        private const val MAX_NOTES_PER_APP = 100
    }

    data class AppNote(
        val note: String,
        val successCount: Int = 1,   // how many times this pattern worked
        val timestamp: Long = System.currentTimeMillis(),
    )

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()

    /**
     * Record a successful task completion for the given app.
     * Extracts a reusable note from the goal + outcome.
     */
    fun saveSuccess(packageName: String, goal: String, outcome: String, actionLog: List<String>) {
        val existing = getNotesRaw(packageName).toMutableList()

        // Build a compact note from what was done
        val noteText = buildString {
            append("Goal: ${goal.take(70).trim()}")
            if (outcome.isNotBlank() && outcome.length > 5) {
                append(" | Result: ${outcome.take(80).trim()}")
            }
            // Include the last few successful actions as a pattern hint
            val meaningfulActions = actionLog
                .filter { !it.contains("MISS") && !it.contains("BLOCKED") && !it.contains("SKIP") }
                .takeLast(4)
            if (meaningfulActions.isNotEmpty()) {
                append(" | Steps: ${meaningfulActions.joinToString(" → ").take(120)}")
            }
        }.take(280)

        // Check if a very similar note already exists — if so, increment its count
        val existingIdx = existing.indexOfFirst {
            it.note.take(50) == noteText.take(50)
        }
        if (existingIdx >= 0) {
            existing[existingIdx] = existing[existingIdx].copy(
                successCount = existing[existingIdx].successCount + 1,
                timestamp = System.currentTimeMillis(),
            )
        } else {
            existing.add(0, AppNote(note = noteText))
        }

        // Keep most-used notes at top, trim to max
        val sorted = existing
            .sortedByDescending { it.successCount * 1000L + it.timestamp / 1000L }
            .take(MAX_NOTES_PER_APP)

        prefs.edit().putString(packageName, gson.toJson(sorted)).apply()
    }

    /**
     * Build a prompt context string for the given app package.
     * Injected into the AI prompt before each task for this app.
     * Returns empty string if no knowledge exists yet.
     */
    fun getPromptContext(packageName: String): String {
        val notes = getNotesRaw(packageName)
        if (notes.isEmpty()) return ""

        return buildString {
            appendLine("📖 KNOWLEDGE BASE — What worked before in this app (use these patterns):")
            notes.forEachIndexed { i, n ->
                val countStr = if (n.successCount > 1) " (worked ${n.successCount}×)" else ""
                appendLine("${i + 1}. ${n.note}$countStr")
            }
            appendLine("Follow these patterns — they are proven to work in this app.")
        }
    }

    fun hasKnowledge(packageName: String): Boolean =
        getNotesRaw(packageName).isNotEmpty()

    fun getAppList(): List<String> = prefs.all.keys.toList()

    fun clearApp(packageName: String) = prefs.edit().remove(packageName).apply()

    fun clearAll() = prefs.edit().clear().apply()

    fun buildSummaryMessage(): String {
        val apps = prefs.all.keys
        if (apps.isEmpty()) return "📭 No app knowledge saved yet.\n\nI'll learn automatically as you use me."
        val sb = StringBuilder("*App Knowledge Base* (${apps.size} apps)\n\n")
        apps.forEach { pkg ->
            val notes = getNotesRaw(pkg)
            val appName = pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
            sb.appendLine("*$appName* — ${notes.size} patterns learned")
        }
        sb.appendLine("\nUse /knowledge clear to reset all, /knowledge clear <app> to reset one.")
        return sb.toString().trimEnd()
    }

    private fun getNotesRaw(packageName: String): List<AppNote> {
        val raw = prefs.getString(packageName, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<AppNote>>() {}.type
            gson.fromJson<List<AppNote>>(raw, type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }
}
