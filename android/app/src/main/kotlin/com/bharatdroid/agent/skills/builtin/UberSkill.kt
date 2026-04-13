package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class UberSkill : Skill {

    override val manifest = SkillManifest(
        id = "uber",
        name = "Uber Cab Booking",
        version = "5.0.0",
        description = "Book cabs on Uber, check ride estimates, track driver — any Uber task",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK, Permission.PAYMENT,
        ),
        allowedPackages = setOf("com.ubercab"),
        exampleParamsHint = """{"action": "book", "destination": "Bangalore Airport"}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase() ?: "book"
        val destination = params["destination"] as? String ?: ""
        val pickup = params["pickup"] as? String ?: ""

        runner.openApp("com.ubercab")
        runner.waitForApp("com.ubercab", timeoutMs = 6000)
        delay(800)
        runner.dismissPopups(3)
        delay(200)

        val goal = when (action) {
            "book" -> {
                if (destination.isBlank()) return SkillResult.Failure("Where do you want to go?")
                """You are in the Uber app. Book a ride to "$destination".
STEPS: 1) Tap the 'Where to?' search field on the home screen. 2) Type "$destination". 3) Tap the most relevant destination suggestion from the dropdown list. 4) Wait for the ride options screen to load showing UberGo, Premier, etc. 5) Tap the most suitable ride option. 6) Review the fare shown. 7) Tap 'Confirm' or 'Request' to book the ride. Stop before entering any payment PIN."""
            }
            "estimate" -> {
                if (destination.isBlank()) return SkillResult.Failure("Where do you want an estimate for?")
                """You are in the Uber app. Get a fare estimate to "$destination"${if (pickup.isNotBlank()) " from \"$pickup\"" else ""}.
STEPS: 1) Tap the 'Where to?' search field. 2) Type "$destination" and tap the best match. 3) Wait for the ride options screen with fare estimates to load. 4) Read out all available ride types and their estimated fares and ETAs shown on screen. Do not tap Confirm — just read the estimates."""
            }
            else ->
                params["goal"] as? String ?: "Do this in Uber: $action $destination".trim()
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 20)
        return SkillResult.Success(result)
    }
}
