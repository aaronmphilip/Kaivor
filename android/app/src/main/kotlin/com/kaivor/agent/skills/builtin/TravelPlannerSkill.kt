package com.kaivor.agent.skills.builtin

import com.kaivor.agent.skills.*
import kotlinx.coroutines.delay

/**
 * TravelPlannerSkill - the hackathon show-stopper.
 *
 * Flow:
 *   1. Open Google Maps ? get directions from CURRENT LOCATION ? destination.
 *   2. Read the ETA (estimated travel time) from the route info.
 *   3. Compute departure time: eventTime - travelTime - bufferMinutes (default 15 min).
 *   4. Open Google Calendar ? create an event at eventTime with a reminder to
 *      leave at the computed departure time.
 *
 * Example params:
 *   {"destination":"Bangalore Airport","eventTitle":"Flight to Delhi","eventTime":"6:00 PM","bufferMinutes":15}
 */
class TravelPlannerSkill : Skill {

    override val manifest = SkillManifest(
        id = "travel_planner",
        name = "Travel Planner (Maps + Calendar)",
        version = "1.2.0",
        description = "Plans trips: gets ETA from Google Maps, adds a calendar event with a 10-15 min buffer to leave on time.",
        author = "kaivor-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK,
        ),
        allowedPackages = setOf(
            "com.google.android.apps.maps",
            "com.google.android.calendar",
        ),
        exampleParamsHint = """{"destination":"Bangalore Airport","eventTitle":"Flight to Delhi","eventTime":"6:00 PM","bufferMinutes":15}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val destination = params["destination"] as? String
            ?: params["to"] as? String
            ?: return SkillResult.Failure("Where are you going? (provide 'destination')")
        val origin = params["origin"] as? String ?: params["from"] as? String ?: "Current location"
        val eventTitle = params["eventTitle"] as? String
            ?: params["title"] as? String
            ?: "Trip to $destination"
        val eventTime = params["eventTime"] as? String ?: params["time"] as? String ?: ""
        val date = params["date"] as? String ?: "today"
        val bufferMinutes = (params["bufferMinutes"] as? Number)?.toInt()
            ?: (params["buffer"] as? Number)?.toInt()
            ?: 15
        val transitMode = (params["mode"] as? String)?.lowercase() ?: "driving" // driving/walking/transit

        // --- Step 1: Open Google Maps and get the ETA ---------------------------
        runner.openApp("com.google.android.apps.maps")
        runner.waitForApp("com.google.android.apps.maps", timeoutMs = 6000)
        delay(700)
        runner.dismissPopups(2)
        delay(200)

        val mapsGoal = buildString {
            append("You are in Google Maps. Your ONLY job is to READ the travel time (ETA) ")
            append("from \"$origin\" to \"$destination\" by $transitMode mode.\n\n")
            append("STEPS:\n")
            append("1. Tap the search bar at the top and type \"$destination\".\n")
            append("2. Tap the best matching result.\n")
            append("3. Tap the 'Directions' button (blue button, usually bottom-left).\n")
            if (origin != "Current location") {
                append("4. In the 'From' field at the top, clear any value and type \"$origin\". Tap the best match.\n")
            } else {
                append("4. Leave the 'From' field as 'Your location' (current location).\n")
            }
            append("5. Make sure the $transitMode tab is selected (car for driving, bus for transit, person for walking).\n")
            append("6. READ the ETA shown (e.g. '23 min', '1 hr 15 min') and the distance.\n")
            append("7. Call done with summary EXACTLY like: 'ETA: 23 min, Distance: 14 km'.\n\n")
            append("DO NOT tap 'Start' or 'Go' - just READ the ETA and stop.")
        }

        val mapsResult = agent.executeGoal(runner, mapsGoal, maxSteps = 50)

        // Parse ETA (minutes) from the Maps result string.
        val etaMinutes = parseEtaMinutes(mapsResult)
        val etaText = extractEtaText(mapsResult)

        if (etaMinutes == null) {
            return SkillResult.Success(
                "Got directions to *$destination* but couldn't parse the ETA automatically.\n" +
                "Maps said: _${mapsResult.take(200)}_\n\n" +
                "Please create the calendar event manually, or re-run with explicit 'eventTime'.",
            )
        }

        val totalBuffer = etaMinutes + bufferMinutes
        val departAdvice = "Leave ${totalBuffer} min before - $etaText travel + $bufferMinutes min buffer."

        // --- Step 2: Open Google Calendar and create the event -----------------
        val calendarResult = createCalendarEventViaIntent(
            runner = runner,
            agent = agent,
            title = eventTitle,
            date = date,
            time = eventTime,
            description = "$departAdvice Travel to $destination.",
        )
        val calResult = when (calendarResult) {
            is SkillResult.Success -> calendarResult.message
            is SkillResult.Failure -> calendarResult.reason
            is SkillResult.NeedsConfirmation -> "Calendar creation needs confirmation."
            is SkillResult.Media -> "Calendar returned media instead of an event result."
        }

        return SkillResult.Success(
            "*Trip planned to $destination*\n" +
                "- Travel time: *$etaText* (via $transitMode)\n" +
                "- Buffer: *$bufferMinutes min*\n" +
                "- $departAdvice\n" +
                "- Calendar: $calResult",
        )
    }

    // -- Helpers ----------------------------------------------------------------

    /**
     * Parses a Maps result string for travel time and returns total minutes.
     * Handles formats like:
     *   "ETA: 23 min"           ? 23
     *   "1 hr 15 min"           ? 75
     *   "2 hours 5 minutes"     ? 125
     *   "45 min"                ? 45
     */
    private fun parseEtaMinutes(text: String): Int? {
        val lower = text.lowercase()

        // Pattern 1: "X hr Y min" or "X hours Y minutes"
        val hrMin = Regex("""(\d+)\s*(?:hr|hrs|hour|hours)\s*(\d+)?\s*(?:min|mins|minute|minutes)?""")
            .find(lower)
        if (hrMin != null) {
            val hrs = hrMin.groupValues[1].toIntOrNull() ?: 0
            val mins = hrMin.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            if (hrs > 0 || mins > 0) return hrs * 60 + mins
        }

        // Pattern 2: just "X min"
        val minOnly = Regex("""(\d+)\s*(?:min|mins|minute|minutes)""").find(lower)
        if (minOnly != null) {
            return minOnly.groupValues[1].toIntOrNull()
        }

        return null
    }

    /** Extracts a human-readable ETA substring from the Maps result. */
    private fun extractEtaText(text: String): String {
        val patterns = listOf(
            Regex("""\d+\s*hr[s]?\s*\d*\s*min[s]?""", RegexOption.IGNORE_CASE),
            Regex("""\d+\s*hour[s]?\s*\d*\s*minute[s]?""", RegexOption.IGNORE_CASE),
            Regex("""\d+\s*min[s]?""", RegexOption.IGNORE_CASE),
        )
        for (p in patterns) {
            p.find(text)?.let { return it.value }
        }
        return "unknown"
    }
}
