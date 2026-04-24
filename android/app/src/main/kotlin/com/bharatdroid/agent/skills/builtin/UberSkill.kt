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
                    append("2. A screen with TWO input fields appears. STOP. Before typing anything, identify each field:\n")
                    append("   PICKUP field (first / top): has placeholder like 'Enter pickup location', 'Choose starting point', 'Current location', 'Add a stop', or already shows your GPS address.\n")
                    append("   DESTINATION field (second / bottom): has placeholder like 'Where to?', 'Enter destination', 'Where are you going?', 'Search destination'.\n")
                    if (!useCurrentPickup) {
                        append("3. Tap INSIDE the TOP pickup field itself. Do NOT tap the tiny X or clear icon beside the field — tapping X dismisses the field without selecting it.\n")
                        append("4. A text cursor must appear inside the pickup field. Clear any existing wrong address (long-press then select-all, or use the clear button ONLY after the field is focused). Then type \"$pickup\".\n")
                        append("   CRITICAL: A suggestion dropdown will appear below the field. You MUST tap one of those suggestions to confirm the pickup. Do NOT tap the X, do NOT press Back, do NOT tap anywhere else — always pick a suggestion.\n")
                        append("5. Only AFTER the pickup suggestion is confirmed, tap INSIDE the BOTTOM destination field. Type \"$destination\" and tap the best suggestion from its dropdown.\n")
                    } else {
                        append("3. The PICKUP field already shows current GPS location. DO NOT tap or clear it.\n")
                        append("4. Tap INSIDE the DESTINATION field (identified by placeholder 'Where to?', 'Enter destination', etc.). Type \"$destination\" and tap the best suggestion.\n")
                    }
                    append("6. If a 'Confirm pickup' map-pin screen appears:\n")
                    append("   - Check that the address shown matches \"${if (useCurrentPickup) "your current location" else pickup}\".\n")
                    append("   - If it matches, tap 'Confirm pickup'.\n")
                    append("   - If it shows a WRONG address, tap the back arrow and re-enter the correct pickup in step 3-4.\n")
                    append("7. Wait for the ride options (Uber Go, Premier, Auto, etc.) with fares to appear.\n")
                    append("8. Tap the cheapest ride option (usually Uber Go or Auto) unless the user specified otherwise.\n")
                    append("9. Review the fare. Tap 'Choose <RideType>' or 'Confirm' or 'Request' to book.\n")
                    append("10. Stop before entering any payment PIN.\n\n")
                    append("STRICT RULES:\n")
                    append("- Do not type the destination into the pickup field or vice-versa. Check which field is top vs bottom first.\n")
                    append("- Do not type placeholder labels like 'Enter pickup location' or 'Where to?'. Only type the actual location names.\n")
                    append("- NEVER tap the tiny X / clear icon to select or focus a field. Tap the field text area itself.\n")
                    append("- After typing the pickup, you MUST tap a suggestion from the dropdown. Never skip the suggestion step.\n")
                    append("- If pickup should use current location or GPS, keep the existing current-location value and do not erase it.\n")
                    append("- If the confirm-pickup screen shows the wrong address, go back and re-enter the correct one.\n")
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
                    append("2. Two fields appear. Identify them first:\n")
                    append("   PICKUP = top field with placeholder like 'Enter pickup location', 'Current location', 'Choose starting point'.\n")
                    append("   DESTINATION = bottom field with placeholder like 'Where to?', 'Enter destination', 'Where are you going?'.\n")
                    if (!useCurrentPickup) {
                        append("3. Tap inside the PICKUP field (not the X icon). Type \"$pickup\". Choose best suggestion. Wait for it to register.\n")
                        append("4. Tap inside the DESTINATION field. Type \"$destination\". Choose best suggestion.\n")
                        append("5. Wait for the ride options screen with fare estimates to load.\n")
                        append("6. Read all visible ride types and their fares / ETAs. Do not confirm the ride.\n")
                    } else {
                        append("3. Leave the PICKUP field as current GPS location. Do not touch it.\n")
                        append("4. Tap inside the DESTINATION field (placeholder 'Where to?' or 'Enter destination'). Type \"$destination\". Choose best suggestion.\n")
                        append("5. Wait for the ride options screen with fare estimates to load.\n")
                        append("6. Read all visible ride types and their fares / ETAs. Do not confirm the ride.\n")
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
