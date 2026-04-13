package com.bharatdroid.agent

import android.content.Context

/**
 * UserMemory — learns how the user wants things done.
 *
 * When the user says "next time do it like this" or "remember that I prefer X",
 * we store a short preference note. These are injected into the AI prompt so the
 * agent remembers and follows the user's way of doing things.
 *
 * Privacy: All data stays on-device in SharedPreferences.
 * If the user turns off AI Learning in Settings, no new memories are saved.
 * They can clear all memories at any time with /forget.
 */
class UserMemory(private val context: Context) {

    companion object {
        private const val PREFS = "bharatdroid_memory"
        private const val KEY_LEARNING_ENABLED = "learning_enabled"
        private const val KEY_MEMORY_LIST = "memory_list"
        private const val MAX_MEMORIES = 30
        private const val SEPARATOR = "|||"

        // Phrases that signal the user wants to teach the agent something
        val LEARN_TRIGGERS = listOf(
            "next time", "remember that", "remember to", "always", "learn that",
            "do it like this", "do it this way", "from now on", "in the future",
            "i want you to", "make sure you", "don't forget to",
            "अगली बार", "याद रखो", "हमेशा",  // Hindi equivalents
        )
    }

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var learningEnabled: Boolean
        get() = prefs.getBoolean(KEY_LEARNING_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_LEARNING_ENABLED, value).apply()

    /** Save a new memory. Returns false if learning is disabled. */
    fun remember(preference: String): Boolean {
        if (!learningEnabled) return false
        val clean = preference.trim().take(200)
        if (clean.isBlank()) return false

        val current = getAll().toMutableList()
        // Don't duplicate very similar memories
        if (current.any { it.equals(clean, ignoreCase = true) }) return true

        current.add(0, clean) // newest first
        val trimmed = current.take(MAX_MEMORIES)
        prefs.edit().putString(KEY_MEMORY_LIST, trimmed.joinToString(SEPARATOR)).apply()
        return true
    }

    /** Get all remembered preferences as a list. */
    fun getAll(): List<String> {
        val raw = prefs.getString(KEY_MEMORY_LIST, "") ?: return emptyList()
        return if (raw.isBlank()) emptyList()
        else raw.split(SEPARATOR).filter { it.isNotBlank() }
    }

    /** Build a compact string of preferences to inject into the AI prompt. */
    fun buildPromptContext(): String {
        val memories = getAll()
        if (memories.isEmpty()) return ""
        return memories.mapIndexed { i, m -> "${i + 1}. $m" }.joinToString("\n")
    }

    /** Remove a specific memory by its 1-based number (as shown in /memory list). */
    fun forget(index: Int): Boolean {
        val current = getAll().toMutableList()
        if (index < 1 || index > current.size) return false
        current.removeAt(index - 1)
        prefs.edit().putString(KEY_MEMORY_LIST, current.joinToString(SEPARATOR)).apply()
        return true
    }

    /** Clear all memories. */
    fun forgetAll() {
        prefs.edit().remove(KEY_MEMORY_LIST).apply()
    }

    /**
     * Detect if a user message is teaching the agent something.
     * Returns the preference text if detected, null otherwise.
     */
    fun extractPreference(message: String): String? {
        val lower = message.lowercase().trim()
        val hasTrigger = LEARN_TRIGGERS.any { lower.contains(it) }
        if (!hasTrigger) return null

        // Extract the meaningful part (skip the trigger phrase itself)
        // e.g. "next time, search before tapping" → "search before tapping"
        // e.g. "remember that I want messages typed, not sent" → "typed messages not auto-sent"
        val preference = message.trim()
        return if (preference.length > 10) preference else null
    }
}
