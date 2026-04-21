package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class UberSkill : Skill {

    override val manifest = SkillManifest(
        id = "uber",
        name = "Uber Cab Booking",
        version = "6.1.0",
        description = "Book cabs on Uber with pickup + destination and support the full ride booking flow",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK, Permission.PAYMENT,
        ),
        allowedPackages = setOf("com.ubercab"),
        exampleParamsHint = """{"action":"book","pickup":"Indiranagar","destination":"Bangalore Airport"}""",
        uiKnowledge = "Uber home screen has a 'Where to?' search bar in the center. Tapping it opens location picker with TWO fields: TOP field = PICKUP (pre-filled as 'Current location'), BOTTOM field = DESTINATION. Always type destination in the BOTTOM field. After selecting locations, a map screen may appear for 'Confirm pickup' — tap that button. Then the ride options screen shows UberGo, Premier, Auto etc with estimated fares and ETAs. Tap the desired ride type, then 'Confirm' or 'Request' to book. Never enter payment PIN.",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase() ?: "book"
        val destination = params["destination"] as? String ?: ""
        val pickup = LocationRequestNormalizer.normalizePickup(params["pickup"] as? String)
        val useCurrentPickup = pickup.isBlank()

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
                    else append(" FROM the current GPS location")
                    append(" TO \"$destination\".\n\n")
                    append("UBER HAS TWO LOCATION FIELDS. READ CAREFULLY:\n")
                    append("- TOP field = pickup location (where the driver picks you up).\n")
                    append("- BOTTOM field = destination (where you want to go).\n")
                    append("- Placeholder text such as 'Enter pickup location', 'Current location', 'Where to?', or 'Search destination' is only a label.\n")
                    append("- NEVER type the placeholder words themselves. Use them only to identify the field, then type the real location value.\n\n")
                    append("STEPS:\n")
                    append("1. Tap the 'Where to?' search field on the home screen.\n")
                    append("2. On the location picker screen, TWO editable fields appear: pickup (TOP) and destination (BOTTOM).\n")
                    if (!useCurrentPickup) {
                        append("3. Tap INSIDE the TOP pickup field itself. Do NOT tap the tiny X or clear icon beside the field.\n")
                        append("4. If the TOP field already contains a wrong real address, clear that address only after the field is focused. Then type \"$pickup\" and tap the best pickup suggestion BEFORE touching destination.\n")
                        append("5. Tap INSIDE the BOTTOM destination field itself. Use its placeholder only to identify the field, then type \"$destination\" and tap the best suggestion.\n")
                    } else {
                        append("3. Leave the TOP pickup field on Uber's current-location / GPS value. If Uber asks to use or confirm the current location, choose that option. Do NOT clear or type the pickup field.\n")
                        append("4. Tap INSIDE the BOTTOM destination field itself. Use its placeholder only as a hint, then type \"$destination\" and tap the best suggestion.\n")
                    }
                    append("6. If a 'Confirm pickup' or map-pin screen appears, tap 'Confirm pickup' to proceed.\n")
                    append("7. Wait for the ride options (Uber Go, Premier, Auto, etc.) with fares to appear.\n")
                    append("8. Tap the cheapest ride option (usually Uber Go or Auto) unless the user specified otherwise.\n")
                    append("9. Review the fare. Tap 'Choose <RideType>' or 'Confirm' or 'Request' to book.\n")
                    append("10. Stop before entering any payment PIN.\n\n")
                    append("STRICT RULES:\n")
                    append("- Do not type the destination into the pickup field or vice-versa. Check which field is top vs bottom first.\n")
                    append("- Do not type placeholder labels like 'Enter pickup location' or 'Where to?'. Only type the actual location names.\n")
                    append("- Never tap the tiny X / clear icon when you mean to select the pickup field.\n")
                    append("- After typing the pickup, choose its suggestion before moving to the destination field.\n")
                    append("- If pickup should use current location or GPS, keep the existing current-location value and do not erase it.\n")
                    append("- Do not tap 'Schedule' or 'Reserve'. Book for right now.\n")
                    append("- If a promo or offer popup appears, dismiss it and continue.\n")
                    append("- Do not enter payment PIN or card details.\n\n")
                    append("FINAL REPLY:\n")
                    append("Ride booked:\n")
                    append("- Pickup: ${if (useCurrentPickup) "Current location" else pickup}\n")
                    append("- Destination: $destination\n")
                    append("- Ride: <ride type>\n")
                    append("- Fare: Rs <amount>\n")
                    append("- Pickup ETA: <eta>")
                }
            }

            "estimate" -> {
                if (destination.isBlank()) return SkillResult.Failure("Where do you want an estimate for?")
                buildString {
                    append("You are in the Uber app. Get a fare estimate")
                    if (pickup.isNotBlank()) append(" from \"$pickup\"")
                    else append(" from the current GPS location")
                    append(" to \"$destination\".\n\n")
                    append("IMPORTANT: placeholder text like 'Enter pickup location', 'Current location', or 'Where to?' is only a UI label. Never type the placeholder words.\n\n")
                    append("STEPS:\n")
                    append("1. Tap the 'Where to?' search field.\n")
                    if (!useCurrentPickup) {
                        append("2. On the two-field picker, tap INSIDE the TOP pickup field itself, not the small X / clear icon.\n")
                        append("3. Type \"$pickup\" into the TOP field and choose the best pickup suggestion BEFORE moving on.\n")
                        append("4. Tap INSIDE the BOTTOM destination field, type \"$destination\", and choose the best suggestion.\n")
                        append("5. Wait for the ride options screen with fare estimates to load.\n")
                        append("6. Read all visible ride types and their fares / ETAs. Do not confirm the ride.\n")
                    } else {
                        append("2. Leave the TOP pickup field as the current location / GPS value. If Uber asks to use or confirm the current location, choose that option.\n")
                        append("3. Tap INSIDE the BOTTOM destination field, type \"$destination\", and choose the best suggestion.\n")
                        append("4. Wait for the ride options screen with fare estimates to load.\n")
                        append("5. Read all visible ride types and their fares / ETAs. Do not confirm the ride.\n")
                    }
                    append("\nSTRICT RULES:\n")
                    append("- Never tap the tiny X / clear icon when you mean to focus the pickup field.\n")
                    append("- Do not erase the current-location pickup when the user wants GPS pickup.\n")
                    append("- Do not type placeholder labels into either field.\n")
                    append("- Do not book or confirm the ride.\n\n")
                    append("FINAL REPLY FORMAT:\n")
                    append("Uber estimates:\n")
                    append("- <ride type> - Fare Rs <amount> - Pickup ETA <eta> - Drop-off <time if visible>\n")
                    append("- <ride type> - Fare Rs <amount> - Pickup ETA <eta> - Drop-off <time if visible>\n")
                    append("One ride per line. Do not merge multiple rides into one paragraph.")
                }
            }

            else ->
                params["goal"] as? String ?: "Do this in Uber: $action $destination".trim()
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 32)
        return SkillResult.Success(result)
    }
}
