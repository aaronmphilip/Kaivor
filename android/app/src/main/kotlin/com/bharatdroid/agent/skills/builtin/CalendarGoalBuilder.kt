package com.bharatdroid.agent.skills.builtin

internal fun buildCalendarCreateGoal(
    title: String,
    date: String?,
    time: String?,
    description: String?,
): String {
    val normalizedDate = date?.trim().orEmpty()
    val normalizedTime = time?.trim().orEmpty()
    val normalizedDescription = description?.trim().orEmpty()

    return buildString {
        append("You are in Google Calendar. Create a NEW event.\n\n")
        append("IMPORTANT RULES:\n")
        append("- If a creation menu appears, choose 'Event'. Never choose Task, Goal, or any template.\n")
        append("- Treat labels such as 'Add title', 'Starts', 'Ends', 'Today', or date chips as UI hints only. Do not type those labels.\n")
        append("- Do not open existing events from the calendar grid or from suggestions with similar names.\n")
        append("- Do not invite guests, switch calendars, or add attachments.\n")
        append("- Only save after the editor shows the requested title, date, and start time.\n\n")
        append("REQUESTED EVENT:\n")
        append("- Title: \"$title\"\n")
        if (normalizedDate.isNotBlank()) {
            append("- Date: \"$normalizedDate\"\n")
        } else {
            append("- Date: keep the default date currently shown.\n")
        }
        if (normalizedTime.isNotBlank()) {
            append("- Start time: \"$normalizedTime\"\n")
        } else {
            append("- Start time: keep the default start time currently shown.\n")
        }
        if (normalizedDescription.isNotBlank()) {
            append("- Description: \"$normalizedDescription\"\n")
        }
        append("\nFOLLOW THIS FLOW:\n")
        append("1. Tap the '+' or 'Create' button.\n")
        append("2. If a chooser appears, tap 'Event'.\n")
        append("3. Tap the title field and type \"$title\".\n")
        if (normalizedDate.isNotBlank()) {
            append("4. Tap the start date field. Use the calendar picker to choose \"$normalizedDate\", then confirm the picker.\n")
        } else {
            append("4. Leave the current date unchanged.\n")
        }
        if (normalizedTime.isNotBlank()) {
            append("5. Tap the start time field. Use the time picker to choose \"$normalizedTime\", then confirm the picker.\n")
        } else {
            append("5. Leave the current start time unchanged.\n")
        }
        if (normalizedDescription.isNotBlank()) {
            append("6. Scroll if needed, tap the Description or Notes field, and type \"$normalizedDescription\".\n")
        } else {
            append("6. Skip the description field.\n")
        }
        append("7. Re-check the event editor before saving. The title must be \"$title\"")
        if (normalizedDate.isNotBlank()) {
            append(", the date must be \"$normalizedDate\"")
        }
        if (normalizedTime.isNotBlank()) {
            append(", and the start time must be \"$normalizedTime\"")
        }
        append(".\n")
        append("8. Tap 'Save'.\n")
        append("9. Verify that the new event opens or appears on the calendar.\n")
    }
}
