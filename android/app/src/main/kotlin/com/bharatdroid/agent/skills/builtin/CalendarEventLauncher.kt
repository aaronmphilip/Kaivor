package com.bharatdroid.agent.skills.builtin

import android.content.Intent
import android.provider.CalendarContract
import com.bharatdroid.agent.ScreenAgent
import com.bharatdroid.agent.skills.SandboxedRunner
import com.bharatdroid.agent.skills.SkillResult
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.format.TextStyle
import java.time.temporal.ChronoField
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private data class PreparedCalendarEvent(
    val title: String,
    val description: String,
    val beginMillis: Long?,
    val endMillis: Long?,
    val allDay: Boolean,
    val resolvedDateLabel: String,
    val resolvedTimeLabel: String,
)

internal suspend fun createCalendarEventViaIntent(
    runner: SandboxedRunner,
    agent: ScreenAgent?,
    title: String,
    date: String?,
    time: String?,
    description: String?,
): SkillResult {
    val prepared = prepareCalendarEvent(title, date, time, description)
    val context = runner.getContext()

    val intent = Intent(Intent.ACTION_INSERT).apply {
        data = CalendarContract.Events.CONTENT_URI
        `package` = "com.google.android.calendar"
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtra(CalendarContract.Events.TITLE, prepared.title)
        if (prepared.description.isNotBlank()) {
            putExtra(CalendarContract.Events.DESCRIPTION, prepared.description)
        }
        prepared.beginMillis?.let { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
        prepared.endMillis?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
        if (prepared.allDay) {
            putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
        }
    }

    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        return SkillResult.Failure("Could not open Google Calendar event editor: ${e.message ?: "unknown error"}")
    }

    runner.waitForApp("com.google.android.calendar", timeoutMs = 7000)
    delay(900)
    runner.dismissPopups(2)
    delay(200)

    if (tapCalendarSave(runner)) {
        runner.waitForScreenChange(timeoutMs = 3500)
        return SkillResult.Success(buildCreatedEventMessage(prepared))
    }

    if (agent != null) {
        val result = agent.executeGoal(
            runner,
            buildString {
                append("You are in Google Calendar on a NEW event editor that is already pre-filled.\n\n")
                append("RULES:\n")
                append("- Do NOT type placeholder text like 'Add title', 'Starts', or 'Today'.\n")
                append("- Do NOT tap random date cells from the background calendar grid.\n")
                append("- Do NOT change the title, date, or time unless they are clearly missing.\n")
                append("- Verify the event editor shows title \"${prepared.title}\"")
                if (prepared.resolvedDateLabel.isNotBlank()) append(", date \"${prepared.resolvedDateLabel}\"")
                if (prepared.resolvedTimeLabel.isNotBlank()) append(", and time \"${prepared.resolvedTimeLabel}\"")
                append(".\n")
                append("- Then tap Save.\n")
                append("- Call done only after the event is saved.\n")
            },
            maxSteps = 70,
        )
        return SkillResult.Success(result)
    }

    return SkillResult.Success(
        "Opened a pre-filled Google Calendar event editor for \"${prepared.title}\". Save it from the Calendar screen.",
    )
}

private suspend fun tapCalendarSave(runner: SandboxedRunner, timeoutMs: Long = 5000): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (runner.tapByText("Save")) return true

        val descriptionNode = runner.findByDescription("Save")
            ?: runner.findByDescription("Done")
        if (descriptionNode != null) {
            if (runner.tap(descriptionNode)) return true
            if (runner.tapAtNode(descriptionNode)) return true
        }

        val (screenW, screenH) = runner.getScreenSize()
        val saveElement = runner.getClickableElements().firstOrNull { el ->
            val label = listOf(el.text, el.hint, el.contentDescription, el.viewId)
                .joinToString(" ")
                .lowercase()
            (label.contains("save") || label.contains("done")) &&
                el.centerY < screenH * 0.22f &&
                el.centerX > screenW * 0.55f
        }
        if (saveElement != null) {
            return runner.tapAtPoint(saveElement.centerX.toFloat(), saveElement.centerY.toFloat())
        }

        delay(250)
    }
    return false
}

private fun buildCreatedEventMessage(prepared: PreparedCalendarEvent): String {
    if (prepared.resolvedDateLabel.isBlank()) {
        return "Created calendar event \"${prepared.title}\"."
    }

    return if (prepared.allDay) {
        "Created calendar event \"${prepared.title}\" on ${prepared.resolvedDateLabel} (all day)."
    } else {
        "Created calendar event \"${prepared.title}\" for ${prepared.resolvedDateLabel} at ${prepared.resolvedTimeLabel}."
    }
}

private fun prepareCalendarEvent(
    title: String,
    date: String?,
    time: String?,
    description: String?,
): PreparedCalendarEvent {
    val zone = ZoneId.systemDefault()
    val now = ZonedDateTime.now(zone)
    val normalizedTitle = title.trim().ifBlank { "New event" }
    val normalizedDescription = description?.trim().orEmpty()
    val normalizedDate = normalizeDateInput(date)
    val normalizedTime = normalizeTimeInput(time)

    val resolvedDate = resolveEventDate(normalizedDate, now)
    val resolvedTime = resolveEventTime(normalizedTime)

    if (resolvedDate == null && resolvedTime == null) {
        return PreparedCalendarEvent(
            title = normalizedTitle,
            description = normalizedDescription,
            beginMillis = null,
            endMillis = null,
            allDay = false,
            resolvedDateLabel = "",
            resolvedTimeLabel = "",
        )
    }

    val targetDate = resolvedDate ?: run {
        val candidate = now.toLocalDate()
        if (resolvedTime != null && resolvedTime.isBefore(now.toLocalTime())) candidate.plusDays(1) else candidate
    }

    if (resolvedTime == null) {
        val start = targetDate.atStartOfDay(zone)
        val end = start.plusDays(1)
        return PreparedCalendarEvent(
            title = normalizedTitle,
            description = normalizedDescription,
            beginMillis = start.toInstant().toEpochMilli(),
            endMillis = end.toInstant().toEpochMilli(),
            allDay = true,
            resolvedDateLabel = targetDate.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())),
            resolvedTimeLabel = "all day",
        )
    }

    val start = ZonedDateTime.of(targetDate, resolvedTime, zone)
    val end = start.plusHours(1)
    return PreparedCalendarEvent(
        title = normalizedTitle,
        description = normalizedDescription,
        beginMillis = start.toInstant().toEpochMilli(),
        endMillis = end.toInstant().toEpochMilli(),
        allDay = false,
        resolvedDateLabel = targetDate.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())),
        resolvedTimeLabel = resolvedTime.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())),
    )
}

internal fun resolveEventDate(raw: String, now: ZonedDateTime): LocalDate? {
    val text = normalizeDateInput(raw)
    if (text.isBlank()) return null

    val lower = text.lowercase(Locale.getDefault())
    val today = now.toLocalDate()

    when (lower) {
        "today" -> return today
        "tomorrow" -> return today.plusDays(1)
        "day after tomorrow" -> return today.plusDays(2)
    }

    parseWeekday(lower, today)?.let { return it }

    val explicitFormatters = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("d/M/uuuu", Locale.getDefault()),
        DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.getDefault()),
        DateTimeFormatter.ofPattern("d-M-uuuu", Locale.getDefault()),
        DateTimeFormatter.ofPattern("dd-MM-uuuu", Locale.getDefault()),
        DateTimeFormatter.ofPattern("d MMM uuuu", Locale.getDefault()),
        DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.getDefault()),
        DateTimeFormatter.ofPattern("MMM d uuuu", Locale.getDefault()),
        DateTimeFormatter.ofPattern("MMMM d uuuu", Locale.getDefault()),
    )
    for (formatter in explicitFormatters) {
        try {
            return LocalDate.parse(text, formatter)
        } catch (_: DateTimeParseException) {
        }
    }

    val noYearFormatters = listOf(
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMM").toFormatter(Locale.getDefault()),
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMMM").toFormatter(Locale.getDefault()),
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("MMM d").toFormatter(Locale.getDefault()),
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("MMMM d").toFormatter(Locale.getDefault()),
    )
    for (formatter in noYearFormatters) {
        try {
            val monthDay = formatter.parse(text) { parsed ->
                val month = parsed.get(ChronoField.MONTH_OF_YEAR)
                val day = parsed.get(ChronoField.DAY_OF_MONTH)
                LocalDate.of(today.year, month, day)
            }
            return if (monthDay.isBefore(today)) monthDay.plusYears(1) else monthDay
        } catch (_: Exception) {
        }
    }

    return null
}

internal fun resolveEventTime(raw: String): LocalTime? {
    val text = normalizeTimeInput(raw)
    if (text.isBlank()) return null

    val formatters = listOf(
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("h:mm a").toFormatter(Locale.getDefault()),
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("h:mma").toFormatter(Locale.getDefault()),
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("h a").toFormatter(Locale.getDefault()),
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("ha").toFormatter(Locale.getDefault()),
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("H:mm").toFormatter(Locale.getDefault()),
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("HH:mm").toFormatter(Locale.getDefault()),
    )

    for (formatter in formatters) {
        try {
            return LocalTime.parse(text, formatter)
        } catch (_: DateTimeParseException) {
        }
    }

    return null
}

private fun normalizeDateInput(raw: String?): String {
    return raw
        ?.trim()
        .orEmpty()
        .replace(Regex("""(?<=\d)(st|nd|rd|th)\b""", RegexOption.IGNORE_CASE), "")
        .replace(",", " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

private fun normalizeTimeInput(raw: String?): String {
    var normalized = raw?.trim().orEmpty()
    if (normalized.isBlank()) return ""

    normalized = normalized.replace('.', ':')
    normalized = normalized.replace(Regex("""\bnoon\b""", RegexOption.IGNORE_CASE), "12:00 PM")
    normalized = normalized.replace(Regex("""\bmidnight\b""", RegexOption.IGNORE_CASE), "12:00 AM")
    normalized = normalized.replace(
        Regex("""\b(\d{1,2}):(\d{2})(am|pm)\b""", RegexOption.IGNORE_CASE),
    ) { match ->
        "${match.groupValues[1]}:${match.groupValues[2]} ${match.groupValues[3].uppercase()}"
    }
    normalized = normalized.replace(
        Regex("""\b(\d{3,4})\s*(am|pm)\b""", RegexOption.IGNORE_CASE),
    ) { match ->
        val digits = match.groupValues[1]
        val hour = digits.dropLast(2).ifBlank { digits }
        val minute = digits.takeLast(2).takeIf { digits.length > 2 } ?: "00"
        "$hour:$minute ${match.groupValues[2].uppercase()}"
    }
    normalized = normalized.replace(
        Regex("""\b(\d{1,2})(am|pm)\b""", RegexOption.IGNORE_CASE),
    ) { match ->
        "${match.groupValues[1]} ${match.groupValues[2].uppercase()}"
    }
    normalized = normalized.replace(Regex("""\s+"""), " ").trim()

    return normalized
}

private fun parseWeekday(text: String, today: LocalDate): LocalDate? {
    val normalized = text.removePrefix("next ").trim()
    val day = DayOfWeek.entries.firstOrNull { weekday ->
        weekday.getDisplayName(TextStyle.FULL, Locale.getDefault()).lowercase(Locale.getDefault()) == normalized ||
            weekday.getDisplayName(TextStyle.SHORT, Locale.getDefault()).lowercase(Locale.getDefault()) == normalized
    } ?: return null

    return if (text.startsWith("next ")) {
        today.with(TemporalAdjusters.next(day))
    } else {
        today.with(TemporalAdjusters.nextOrSame(day))
    }
}
