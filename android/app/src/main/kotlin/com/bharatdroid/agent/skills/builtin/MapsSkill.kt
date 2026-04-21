package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class MapsSkill : Skill {

    override val manifest = SkillManifest(
        id = "maps",
        name = "Google Maps Navigation",
        version = "6.0.0",
        description = "Navigate, search places, get directions, explore nearby on Google Maps",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK,
        ),
        allowedPackages = setOf("com.google.android.apps.maps"),
        exampleParamsHint = """{"action": "navigate", "destination": "Connaught Place, Delhi"}""",
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

        runner.openApp("com.google.android.apps.maps")
        runner.waitForApp("com.google.android.apps.maps", timeoutMs = 7000)
        delay(700)
        runner.dismissPopups(2)
        delay(300)

        // For destination-based actions, type directly into the search bar first
        if (destination != null && action in setOf("navigate", "search", "directions", "explore")) {
            val typed = runner.typeInFieldWithHint("Search here", destination)
                || runner.typeInFieldWithHint("Search", destination)
            if (typed) {
                delay(1200) // wait for autocomplete
            } else {
                // Fallback: tap the search bar at the top (~7% from top, centered)
                val (w, h) = runner.getScreenSize()
                runner.tapAtPoint(w * 0.5f, h * 0.07f)
                delay(500)
                runner.typeReliably(destination)
                delay(1200)
            }
        }

        if (destination == null && action !in setOf("nearby", "home", "goal")) {
            return SkillResult.Failure("Where do you want to go?")
        }

        val goal = when (action) {
            "navigate" ->
                """You are in Google Maps. The search bar already has "$destination" typed or autocomplete suggestions are visible.
STEPS:
1. Tap the most relevant autocomplete suggestion for "$destination"
2. Wait for the place detail card to appear
3. Tap the blue "Directions" button
4. Tap "Start" or "GO" to begin navigation
5. Confirm navigation has started — report the route and ETA"""

            "directions" ->
                """You are in Google Maps. The search bar has "$destination" typed.
STEPS:
1. Tap the best autocomplete match for "$destination"
2. Tap "Directions" on the place detail card
3. Read out the available routes — travel mode, estimated time, distance
4. Do NOT tap Start"""

            "search" ->
                """You are in Google Maps. The search bar has "$destination" typed.
STEPS:
1. Tap the best autocomplete suggestion for "$destination"
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

        val result = agent.executeGoal(runner, goal, maxSteps = 18)
        return SkillResult.Success(result)
    }
}
