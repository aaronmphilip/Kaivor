package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class MapsSkill : Skill {

    override val manifest = SkillManifest(
        id = "maps",
        name = "Google Maps Navigation",
        version = "5.0.0",
        description = "Navigate, search places, get directions on Google Maps",
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
""".trimIndent(),
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase() ?: "navigate"
        val destination = params["destination"] as? String ?: params["query"] as? String
            ?: params["goal"] as? String ?: return SkillResult.Failure("Where do you want to go?")

        runner.openApp("com.google.android.apps.maps")
        runner.waitForApp("com.google.android.apps.maps", timeoutMs = 6000)
        delay(600)
        runner.dismissPopups(2)
        delay(200)

        val goal = when (action) {
            "navigate" ->
                """You are in Google Maps. Navigate to "$destination".
                STEPS: 1) Tap the search bar at the top. 2) Type "$destination". 3) Wait for results. 4) Tap the most relevant result. 5) Tap the Directions button. 6) Tap Start or GO to begin navigation."""

            "search" ->
                """You are in Google Maps. Search for "$destination".
                STEPS: 1) Tap the search bar. 2) Type "$destination". 3) Wait for results. 4) Tap the most relevant place."""

            "directions" ->
                """You are in Google Maps. Get directions to "$destination".
                STEPS: 1) Tap the search bar. 2) Type "$destination". 3) Tap the result. 4) Tap Directions. 5) Show the route options."""

            else -> params["goal"] as? String ?: "Do this in Google Maps: $destination"
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 15)
        return SkillResult.Success(result)
    }
}
