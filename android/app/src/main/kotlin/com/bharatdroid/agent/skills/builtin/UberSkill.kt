package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class UberSkill : Skill {

    override val manifest = SkillManifest(
        id = "uber",
        name = "Uber Cab Booking",
        version = "6.0.0",
        description = "Book cabs on Uber with pickup + destination — supports full ride booking flow",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK, Permission.PAYMENT,
        ),
        allowedPackages = setOf("com.ubercab"),
        exampleParamsHint = """{"action":"book","pickup":"Indiranagar","destination":"Bangalore Airport"}""",
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
                buildString {
                    append("You are in the Uber app. Book a ride")
                    if (pickup.isNotBlank()) append(" FROM \"$pickup\"")
                    append(" TO \"$destination\".\n\n")
                    append("⚠️ UBER HAS TWO LOCATION FIELDS. READ CAREFULLY:\n")
                    append("- TOP field = PICKUP location (where the driver picks you up). Usually pre-filled with 'Current location'.\n")
                    append("- BOTTOM field = DESTINATION (where you want to go).\n")
                    append("You MUST type into the correct field. Read the hint/label on each field before typing.\n\n")
                    append("STEPS:\n")
                    append("1. Tap the 'Where to?' search field on the home screen.\n")
                    append("2. On the location picker screen, TWO editable fields appear: pickup (TOP) and destination (BOTTOM).\n")
                    if (pickup.isNotBlank()) {
                        append("3. TAP THE TOP FIELD (pickup). If it shows 'Current location' or an address, clear it first, then type \"$pickup\".\n")
                        append("   Wait for suggestions to appear. TAP the best match for \"$pickup\" from the dropdown.\n")
                        append("4. Now TAP THE BOTTOM FIELD (destination). Type \"$destination\".\n")
                        append("   Wait for suggestions. TAP the best match for \"$destination\" from the dropdown.\n")
                    } else {
                        append("3. Leave the PICKUP field as 'Current location' (already correct).\n")
                        append("4. TAP THE BOTTOM FIELD (destination). Type \"$destination\".\n")
                        append("   Wait for suggestions. TAP the best match for \"$destination\" from the dropdown.\n")
                    }
                    append("5. If a 'Confirm pickup' or map-pin screen appears, tap 'Confirm pickup' to proceed.\n")
                    append("6. Wait for the ride options (UberGo, Premier, Auto, etc.) with fares to appear.\n")
                    append("7. TAP the cheapest ride option (usually UberGo or Auto) UNLESS the user specified otherwise.\n")
                    append("8. Review the fare. TAP 'Choose <RideType>' or 'Confirm' or 'Request' to book.\n")
                    append("9. STOP before entering any payment PIN. Report the ride type, fare, and ETA.\n\n")
                    append("STRICT RULES:\n")
                    append("- DO NOT type the destination into the pickup field or vice-versa — check hints first.\n")
                    append("- DO NOT tap 'Schedule' or 'Reserve' — book for RIGHT NOW.\n")
                    append("- If a promo/offer popup appears, dismiss it and continue.\n")
                    append("- DO NOT enter payment PIN or card details.")
                }
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
