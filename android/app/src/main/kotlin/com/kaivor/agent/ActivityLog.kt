package com.kaivor.agent

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────
// ACTIVITY LOG
//
// Stores the last N agent actions so the dashboard
// and Telegram /history command can show them.
// Persisted to SharedPreferences as JSON.
// ─────────────────────────────────────────────

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val command: String,
    val skill: String?,       // null if direct reply
    val result: String,       // "success" or "failure"
    val summary: String,      // short result message
) {
    val timeFormatted: String get() {
        val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    val dateFormatted: String get() {
        val sdf = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}

class ActivityLog(context: Context) {
    private val prefs = context.getSharedPreferences("kaivor_log", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val maxEntries = 2000

    fun log(command: String, skill: String?, result: String, summary: String) {
        val entries = getAll().toMutableList()
        entries.add(0, LogEntry(
            command = command,
            skill = skill,
            result = result,
            summary = summary.take(1000),
        ))
        // Trim
        while (entries.size > maxEntries) entries.removeLast()
        save(entries)
    }

    fun getAll(): List<LogEntry> {
        val json = prefs.getString("entries", null) ?: return emptyList()
        val type = object : TypeToken<List<LogEntry>>() {}.type
        return try { gson.fromJson(json, type) } catch (_: Exception) { emptyList() }
    }

    fun getRecent(n: Int = 10): List<LogEntry> = getAll().take(n)

    fun clear() {
        prefs.edit().remove("entries").apply()
    }

    fun todayCount(): Int {
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis
        return getAll().count { it.timestamp >= todayStart }
    }

    fun buildHistoryMessage(n: Int = 10): String {
        val entries = getRecent(n)
        if (entries.isEmpty()) return "No activity yet."
        return "*Recent Activity:*\n\n" + entries.joinToString("\n\n") { e ->
            val icon = if (e.result == "success") "✓" else "✗"
            "$icon `${e.timeFormatted}` — ${e.command.take(40)}\n   → ${e.summary.take(80)}"
        }
    }

    private fun save(entries: List<LogEntry>) {
        prefs.edit().putString("entries", gson.toJson(entries)).apply()
    }
}
