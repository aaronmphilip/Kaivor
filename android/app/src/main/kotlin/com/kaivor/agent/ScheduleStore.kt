package com.kaivor.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar
import java.util.UUID

/**
 * ScheduleStore — lets users schedule commands to run at a specific time.
 *
 * Usage:
 *   /schedule 7pm order pizza         → today 7 PM (tomorrow if past)
 *   /schedule 9:30am book Ola         → today 9:30 AM (tomorrow if past)
 *   /schedule tomorrow 8am brief      → tomorrow 8 AM
 *   /schedule daily 7am brief         → today 7 AM, repeats every 24h
 *   /schedule in 30 minutes check email → now + 30 min
 *   /schedule list                    → show all scheduled tasks
 *   /schedule cancel 3                → cancel task #3
 *   /schedule clear                   → cancel all tasks
 */
class ScheduleStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("kaivor_schedule", Context.MODE_PRIVATE)
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    companion object {
        private const val KEY_TASKS = "scheduled_tasks"
        private const val SEPARATOR = "||||"
        private const val FIELD_SEP = "^^^"

        const val EXTRA_SCHEDULE_ID = "schedule_id"
        const val EXTRA_COMMAND = "schedule_command"
        const val EXTRA_CHAT_ID = "schedule_chat_id"
        const val ACTION_RUN_SCHEDULED = "com.kaivor.agent.RUN_SCHEDULED"
    }

    data class ScheduledTask(
        val id: String,
        val command: String,
        val triggerMs: Long,
        val recurring: Boolean,
        val recurDescription: String,   // e.g. "daily 7am"
        val chatId: Long,
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Entry point for /schedule command.
     * Routes "list", "cancel N", "clear", or parses a schedule spec.
     */
    fun handleCommand(args: String, chatId: Long): String {
        val trimmed = args.trim()
        val lower = trimmed.lowercase()

        if (trimmed.isBlank() || lower == "list" || lower == "show") {
            return buildListMessage()
        }
        if (lower == "clear") {
            val tasks = loadAll()
            tasks.forEach { cancelAlarm(it) }
            saveAll(emptyList())
            return if (tasks.isEmpty()) "No scheduled tasks to clear." else "🗑️ Cleared ${tasks.size} scheduled task${if (tasks.size != 1) "s" else ""}."
        }
        if (lower.startsWith("cancel ") || lower.startsWith("delete ")) {
            val numStr = trimmed.substringAfter(" ").trim()
            val num = numStr.toIntOrNull()
            if (num == null || num < 1) return "Usage: `/schedule cancel <number>`\nSee `/schedule list` for task numbers."
            val tasks = loadAll()
            if (num > tasks.size) return "No task #$num. You have ${tasks.size} scheduled task${if (tasks.size != 1) "s" else ""}."
            val task = tasks[num - 1]
            return cancelById(task.id)
        }
        return schedule(trimmed, chatId)
    }

    /**
     * Parse a schedule spec and create the alarm.
     * Returns a user-facing confirmation or error message.
     */
    fun schedule(args: String, chatId: Long): String {
        val trimmed = args.trim()
        if (trimmed.isBlank()) return buildListMessage()

        val parsed = parseScheduleSpec(trimmed)
            ?: return "❌ Couldn't understand the schedule. Try:\n" +
                "`/schedule 7pm order pizza`\n" +
                "`/schedule 9:30am book Ola`\n" +
                "`/schedule tomorrow 8am brief`\n" +
                "`/schedule daily 7am morning brief`\n" +
                "`/schedule in 30 minutes check email`"

        val (triggerMs, recurring, recurDesc, command) = parsed
        if (command.isBlank()) return "❌ No command found. Example: `/schedule 7pm order pizza`"

        val task = ScheduledTask(
            id = UUID.randomUUID().toString(),
            command = command,
            triggerMs = triggerMs,
            recurring = recurring,
            recurDescription = recurDesc,
            chatId = chatId,
        )

        val tasks = loadAll().toMutableList()
        tasks.add(task)
        saveAll(tasks)
        scheduleAlarm(task)

        val timeStr = formatTime(triggerMs)
        return if (recurring) {
            "⏰ *Scheduled (recurring):* _${command.take(60)}_\nRuns $recurDesc starting $timeStr."
        } else {
            "⏰ *Scheduled:* _${command.take(60)}_\nRuns at $timeStr."
        }
    }

    /**
     * Called by ScheduledCommandReceiver when an alarm fires.
     * Returns the command to run, removes one-time task or reschedules recurring.
     */
    fun onFired(id: String): String? {
        val tasks = loadAll().toMutableList()
        val task = tasks.firstOrNull { it.id == id } ?: return null
        val command = task.command

        if (task.recurring) {
            // Reschedule for next day at the same time
            val nextTrigger = task.triggerMs + 24L * 60 * 60 * 1000
            val updated = task.copy(triggerMs = nextTrigger)
            val idx = tasks.indexOfFirst { it.id == id }
            tasks[idx] = updated
            saveAll(tasks)
            scheduleAlarm(updated)
        } else {
            tasks.removeAll { it.id == id }
            saveAll(tasks)
        }

        return command
    }

    /**
     * Cancel a task by its UUID. Returns a user-facing message.
     */
    fun cancelById(id: String): String {
        val tasks = loadAll().toMutableList()
        val task = tasks.firstOrNull { it.id == id }
            ?: return "Task not found."
        cancelAlarm(task)
        tasks.removeAll { it.id == id }
        saveAll(tasks)
        return "✅ Cancelled: _${task.command.take(60)}_"
    }

    /**
     * Build a Telegram-formatted list of all scheduled tasks.
     */
    fun buildListMessage(): String {
        val tasks = loadAll()
        if (tasks.isEmpty()) {
            return "⏰ No scheduled tasks.\n\nSchedule one:\n`/schedule 7pm order pizza`\n`/schedule daily 7am morning brief`\n`/schedule in 30 minutes check email`"
        }
        return buildString {
            appendLine("⏰ *Scheduled Tasks (${tasks.size}):*")
            appendLine()
            tasks.forEachIndexed { i, task ->
                val timeStr = formatTime(task.triggerMs)
                val recurStr = if (task.recurring) " _(${task.recurDescription})_" else ""
                appendLine("${i + 1}. _${task.command.take(60)}_")
                appendLine("   🕐 $timeStr$recurStr")
            }
            appendLine()
            append("Cancel with `/schedule cancel <number>` · Clear all with `/schedule clear`")
        }.trim()
    }

    // ── Time parsing ──────────────────────────────────────────────────────────

    private data class ParsedSpec(
        val triggerMs: Long,
        val recurring: Boolean,
        val recurDescription: String,
        val command: String,
    )

    /**
     * Parses a schedule specification string.
     * Returns null if it can't be parsed.
     */
    private fun parseScheduleSpec(input: String): ParsedSpec? {
        val lower = input.lowercase()
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()

        // "in N minutes/hours command"
        val inMinutesRe = Regex("""^in\s+(\d+)\s+minutes?\s+(.+)$""", RegexOption.IGNORE_CASE)
        val inHoursRe = Regex("""^in\s+(\d+)\s+hours?\s+(.+)$""", RegexOption.IGNORE_CASE)
        inMinutesRe.matchEntire(input.trim())?.let { m ->
            val mins = m.groupValues[1].toLongOrNull() ?: return null
            val cmd = m.groupValues[2].trim()
            return ParsedSpec(now + mins * 60_000L, false, "", cmd)
        }
        inHoursRe.matchEntire(input.trim())?.let { m ->
            val hrs = m.groupValues[1].toLongOrNull() ?: return null
            val cmd = m.groupValues[2].trim()
            return ParsedSpec(now + hrs * 3_600_000L, false, "", cmd)
        }

        // "daily HH:mm[am|pm] command"
        val dailyRe = Regex("""^daily\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\s+(.+)$""", RegexOption.IGNORE_CASE)
        dailyRe.matchEntire(input.trim())?.let { m ->
            val (hour, minute) = parseHourMinute(m.groupValues[1], m.groupValues[2], m.groupValues[3]) ?: return null
            val cmd = m.groupValues[4].trim()
            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, minute)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
            val desc = "daily ${formatHourMinute(hour, minute)}"
            return ParsedSpec(cal.timeInMillis, true, desc, cmd)
        }

        // "tomorrow HH:mm[am|pm] command"
        val tomorrowRe = Regex("""^tomorrow\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\s*(.*)$""", RegexOption.IGNORE_CASE)
        tomorrowRe.matchEntire(input.trim())?.let { m ->
            val (hour, minute) = parseHourMinute(m.groupValues[1], m.groupValues[2], m.groupValues[3]) ?: return null
            val cmd = m.groupValues[4].trim()
            cal.add(Calendar.DAY_OF_YEAR, 1)
            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, minute)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return ParsedSpec(cal.timeInMillis, false, "", cmd)
        }

        // "HH:mm[am|pm] command" or "HH[am|pm] command"
        val timeRe = Regex("""^(\d{1,2})(?::(\d{2}))?\s*(am|pm)\s+(.+)$""", RegexOption.IGNORE_CASE)
        timeRe.matchEntire(input.trim())?.let { m ->
            val (hour, minute) = parseHourMinute(m.groupValues[1], m.groupValues[2], m.groupValues[3]) ?: return null
            val cmd = m.groupValues[4].trim()
            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, minute)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
            return ParsedSpec(cal.timeInMillis, false, "", cmd)
        }

        return null
    }

    /**
     * Parse hour/minute from string components, applying am/pm.
     * Returns Pair(hour24, minute) or null on error.
     */
    private fun parseHourMinute(hourStr: String, minuteStr: String, ampm: String): Pair<Int, Int>? {
        val rawHour = hourStr.toIntOrNull() ?: return null
        val minute = if (minuteStr.isBlank()) 0 else minuteStr.toIntOrNull() ?: return null
        if (minute < 0 || minute > 59) return null

        val hour = when {
            ampm.isBlank() -> rawHour  // 24h format assumed
            ampm.equals("am", ignoreCase = true) -> if (rawHour == 12) 0 else rawHour
            ampm.equals("pm", ignoreCase = true) -> if (rawHour == 12) 12 else rawHour + 12
            else -> rawHour
        }
        if (hour < 0 || hour > 23) return null
        return Pair(hour, minute)
    }

    private fun formatHourMinute(hour: Int, minute: Int): String {
        val ampm = if (hour < 12) "AM" else "PM"
        val h = if (hour == 0 || hour == 12) 12 else hour % 12
        return if (minute == 0) "${h}${ampm}" else "${h}:${minute.toString().padStart(2, '0')}${ampm}"
    }

    private fun formatTime(ms: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        val today = Calendar.getInstance()
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }

        val timeStr = formatHourMinute(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        return when {
            cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) &&
            cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> "today $timeStr"

            cal.get(Calendar.DAY_OF_YEAR) == tomorrow.get(Calendar.DAY_OF_YEAR) &&
            cal.get(Calendar.YEAR) == tomorrow.get(Calendar.YEAR) -> "tomorrow $timeStr"

            else -> {
                val day = cal.get(Calendar.DAY_OF_MONTH)
                val month = cal.getDisplayName(Calendar.MONTH, Calendar.SHORT, java.util.Locale.getDefault())
                "$day $month $timeStr"
            }
        }
    }

    // ── AlarmManager wiring ───────────────────────────────────────────────────

    private fun scheduleAlarm(task: ScheduledTask) {
        val intent = Intent(ACTION_RUN_SCHEDULED).apply {
            setClass(context, ScheduledCommandReceiver::class.java)
            putExtra(EXTRA_SCHEDULE_ID, task.id)
            putExtra(EXTRA_COMMAND, task.command)
            putExtra(EXTRA_CHAT_ID, task.chatId)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            task.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.triggerMs, pi)
    }

    private fun cancelAlarm(task: ScheduledTask) {
        val intent = Intent(ACTION_RUN_SCHEDULED).apply {
            setClass(context, ScheduledCommandReceiver::class.java)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            task.id.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        pi?.let { alarmManager.cancel(it) }
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun loadAll(): List<ScheduledTask> {
        val raw = prefs.getString(KEY_TASKS, "") ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split(SEPARATOR).mapNotNull { entry ->
            val parts = entry.split(FIELD_SEP)
            if (parts.size < 6) return@mapNotNull null
            runCatching {
                ScheduledTask(
                    id = parts[0],
                    command = parts[1],
                    triggerMs = parts[2].toLong(),
                    recurring = parts[3].toBoolean(),
                    recurDescription = parts[4],
                    chatId = parts[5].toLong(),
                )
            }.getOrNull()
        }
    }

    private fun saveAll(tasks: List<ScheduledTask>) {
        val serialized = tasks.joinToString(SEPARATOR) { t ->
            listOf(t.id, t.command, t.triggerMs, t.recurring, t.recurDescription, t.chatId)
                .joinToString(FIELD_SEP)
        }
        prefs.edit().putString(KEY_TASKS, serialized).apply()
    }
}
