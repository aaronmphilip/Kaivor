package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class MapsSkill : Skill {

    override val manifest = SkillManifest(
        id = "maps",
        name = "Google Maps Navigation",
        version = "6.0.0",
        description = "Navigate, get directions, compare ETA across travel modes, search places and explore nearby on Google Maps. Supports custom pickup/origin address.",
        author = "bharatclaw-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK,
        ),
        allowedPackages = setOf("com.google.android.apps.maps"),
        exampleParamsHint = """{"action": "navigate", "destination": "Connaught Place, Delhi", "pickup": "Indiranagar"} | {"action": "eta_compare", "destination": "Airport"} | {"action": "directions", "destination": "Lajpat Nagar"}""",
        uiKnowledge = """
Google Maps UI guide:
- Home screen: search bar at top reads "Search here"; blue dot on map = your current location
- After typing destination: autocomplete suggestions appear below the search bar; tap the most relevant one
- Place detail card: shows after tapping a result — name, address, rating, hours; blue "Directions" button at bottom
- Directions screen: shows route options (car/transit/walk tabs), estimated time and distance; blue "Start" button at bottom right
- Navigation mode: turn-by-turn arrow at top with street name and distance; speed limit badge on right; "Exit navigation" button at top left
- Search results: vertical list of places with name, address, distance, and rating below the search bar
- Layers button: bottom left corner, opens map type selector (satellite, terrain, etc.)
- My Location FAB: circular button bottom right, re-centres map on blue dot
- Nearby: after tapping the search bar without typing, category shortcuts appear (Restaurants, Petrol, ATM, Coffee, etc.)
""".trimIndent(),
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase() ?: "navigate"
        val destination = params["destination"] as? String ?: params["query"] as? String
            ?: params["place"] as? String ?: params["goal"] as? String
        val pickup = (params["pickup"] as? String ?: params["from"] as? String ?: params["origin"] as? String)
            ?.trim()?.takeIf { it.isNotBlank() && !it.equals("current location", ignoreCase = true) && !it.equals("my location", ignoreCase = true) }

        runner.openApp("com.google.android.apps.maps")
        runner.waitForApp("com.google.android.apps.maps", timeoutMs = 7000)
        delay(200)
        runner.dismissPopups(2)
        delay(100)

        // For destination-based actions, type directly into the search bar first
        if (destination != null && action in setOf("navigate", "search", "directions", "explore")) {
            val typed = runner.typeInFieldWithHint("Search here", destination)
                || runner.typeInFieldWithHint("Search", destination)
            if (typed) {
                delay(1500) // wait for autocomplete
            } else {
                // Fallback: tap the search bar at the top (~7% from top, centered)
                val (w, h) = runner.getScreenSize()
                runner.tapAtPoint(w * 0.5f, h * 0.07f)
                delay(500)
                runner.typeReliably(destination)
                delay(1500)
            }
        }

        if (destination == null && action !in setOf("nearby", "home", "goal", "eta_compare")) {
            return SkillResult.Failure("Where do you want to go?")
        }

        val goal = when (action) {
            "navigate" -> buildNavigateGoal(destination!!, pickup)

            "directions" -> buildDirectionsGoal(destination!!, pickup)

            "eta_compare" -> buildEtaCompareGoal(destination ?: "", pickup)

            "search" ->
                """You are in Google Maps. The search bar has "$destination" typed.
STEPS:
1. Tap the best autocomplete suggestion for "$destination"
   CRITICAL: Do NOT type again. Look for text items appearing below the search bar and tap one.
2. When the place detail card opens, read out: name, address, rating, opening hours, phone number if visible
3. Report what you found"""

            "nearby" -> {
                val category = destination ?: "Restaurants"
                """You are in Google Maps. Search for nearby "$category".
STEPS:
1. Tap the search bar at the top
2. Type "$category"
3. Wait for suggestions and results
4. Tap the most relevant result or browse the list
5. Report the top 3-5 nearby options — name, rating, distance"""
            }

            "share_location" ->
                """You are in Google Maps.
STEPS:
1. Tap and hold on the blue 'My Location' dot to drop a pin at current location
2. Tap "Share" on the pin card that appears
3. Read out the location coordinates or address shown"""

            else ->
                params["goal"] as? String ?: "Do this in Google Maps: $action ${destination ?: ""}".trim()
        }

        val maxSteps = if (action == "eta_compare") 30 else 22
        val result = agent.executeGoal(runner, goal, maxSteps = maxSteps)
        return SkillResult.Success(result)
    }

    private fun buildNavigateGoal(destination: String, pickup: String?): String = buildString {
        appendLine("You are in Google Maps. The search bar already has \"$destination\" typed and autocomplete suggestions should be visible below it.")
        appendLine()
        appendLine("══════ CRITICAL — READ THIS BEFORE DOING ANYTHING ══════")
        appendLine("The destination is ALREADY typed. Do NOT type it again. Do NOT press a search button.")
        appendLine("Autocomplete suggestions (clickable text items) are appearing BELOW the search bar.")
        appendLine("Your FIRST action must be: TAP THE BEST AUTOCOMPLETE SUGGESTION for \"$destination\".")
        appendLine("If no suggestions are visible yet, wait 2 seconds then look again before doing anything.")
        appendLine("═════════════════════════════════════════════════════════")
        appendLine()
        appendLine("STEPS:")
        appendLine("1. TAP the most relevant autocomplete suggestion for \"$destination\" from the list below the search bar.")
        appendLine("   → The search bar closes. A place detail card appears (name, address, rating, blue Directions button).")
        appendLine("2. Wait for the place detail card to fully load.")
        appendLine("3. Tap the blue \"Directions\" button on the place detail card.")
        appendLine()
        appendLine("─── DIRECTIONS SCREEN — READ THIS CAREFULLY ───────────────────────────────")
        appendLine("After tapping Directions, a new screen opens with TWO route input fields at the top:")
        if (pickup != null) {
            appendLine("  FIELD 1 (top / FROM): currently shows your location — TAP THIS FIELD and change it to \"$pickup\"")
            appendLine("    → Tap the FROM field text area, clear existing text, type \"$pickup\" (5–7 chars), tap best suggestion.")
            appendLine("  FIELD 2 (bottom / TO): shows \"$destination\" — DO NOT touch this field, it is already set correctly.")
        } else {
            appendLine("  FIELD 1 (top / FROM): \"Your location\" — DO NOT touch this field, it is already set correctly.")
            appendLine("  FIELD 2 (bottom / TO): \"$destination\" — DO NOT touch this field, it is already set correctly.")
        }
        appendLine("DO NOT re-type \"$destination\" into the FROM field. DO NOT search again.")
        appendLine("────────────────────────────────────────────────────────────────────────────")
        appendLine()
        appendLine("4. Wait for the route to calculate (time and distance appear).")
        appendLine("5. Tap \"Start\" or \"GO\" (the large blue button at the bottom right) to begin navigation.")
        appendLine("6. Report the first turn instruction, route ETA, and distance.")
    }

    private fun buildDirectionsGoal(destination: String, pickup: String?): String = buildString {
        appendLine("You are in Google Maps. The search bar already has \"$destination\" typed and autocomplete suggestions should be visible below it.")
        appendLine()
        appendLine("══════ CRITICAL — READ THIS BEFORE DOING ANYTHING ══════")
        appendLine("The destination is ALREADY typed. Do NOT type it again. Do NOT press a search button.")
        appendLine("Autocomplete suggestions (clickable text items) are appearing BELOW the search bar.")
        appendLine("Your FIRST action must be: TAP THE BEST AUTOCOMPLETE SUGGESTION for \"$destination\".")
        appendLine("If no suggestions are visible yet, wait 2 seconds then look again before doing anything.")
        appendLine("═════════════════════════════════════════════════════════")
        appendLine()
        appendLine("STEPS:")
        appendLine("1. TAP the best autocomplete suggestion for \"$destination\".")
        appendLine("2. Tap \"Directions\" on the place detail card.")
        appendLine()
        appendLine("─── DIRECTIONS SCREEN ───────────────────────────────────────────────────────")
        if (pickup != null) {
            appendLine("  FIELD 1 (top / FROM): tap it and change to \"$pickup\" — type 5–7 chars, tap best suggestion.")
            appendLine("  FIELD 2 (bottom / TO): \"$destination\" — DO NOT change this.")
        } else {
            appendLine("  FIELD 1 (top / FROM): \"Your location\" — DO NOT change this.")
            appendLine("  FIELD 2 (bottom / TO): \"$destination\" — DO NOT change this.")
        }
        appendLine("────────────────────────────────────────────────────────────────────────────")
        appendLine()
        appendLine("3. Select the driving (car) mode tab if not already selected.")
        appendLine("4. READ the route information shown: ETA, distance, alternate routes.")
        appendLine("5. Do NOT tap Start. Report what you see — time, distance, any alternate routes.")
    }

    private fun buildEtaCompareGoal(destination: String, pickup: String?): String = buildString {
        appendLine("You are in Google Maps. Compare travel time from ${pickup ?: "current location"} to \"$destination\" across different travel modes.")
        appendLine()
        if (destination.isNotBlank()) {
            appendLine("STEPS:")
            appendLine("1. Tap the search bar and type \"$destination\" (5–7 chars).")
            appendLine("2. Tap the best autocomplete suggestion.")
            appendLine("3. Tap the \"Directions\" button on the place detail card.")
            appendLine()
            if (pickup != null) {
                appendLine("4. On the directions screen, tap the FROM (top) field and change it to \"$pickup\".")
                appendLine("   Type \"$pickup\" (5–7 chars), tap best suggestion.")
            } else {
                appendLine("4. Leave the FROM (top) field as current location.")
            }
            appendLine()
            appendLine("5. Check each travel mode tab and read the ETA and distance for each:")
            appendLine("   - Driving (car icon) — ETA and distance")
            appendLine("   - Transit/Bus (bus icon) — ETA and transit options")
            appendLine("   - Walking (person icon) — ETA and distance")
            appendLine("   - Two-wheeler (bike/scooter icon, if available) — ETA and distance")
            appendLine("6. Do NOT tap Start or Go.")
            appendLine()
            appendLine("FINAL REPLY — list EXACT values from screen for each mode:")
            appendLine("ETA comparison to $destination:")
            appendLine("- Driving: [time] ([distance])")
            appendLine("- Transit: [time]")
            appendLine("- Walking: [time]")
            appendLine("- Two-wheeler: [time] (if shown)")
        } else {
            appendLine("Read the current directions screen and report ETAs for all visible travel modes.")
        }
    }
}
