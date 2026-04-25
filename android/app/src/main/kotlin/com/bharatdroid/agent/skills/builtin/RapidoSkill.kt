package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class RapidoSkill : Skill {

    override val manifest = SkillManifest(
        id = "rapido",
        name = "Rapido Bike/Auto Booking",
        version = "1.0.0",
        description = "Book bike taxis, autos, or cabs on Rapido with pickup + destination support",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK, Permission.PAYMENT,
        ),
        allowedPackages = setOf("com.rapido.passenger"),
        exampleParamsHint = """{"destination":"Koramangala","action":"book","transport":"bike"}""",
        uiKnowledge = """
Rapido app UI guide:
- Home screen: Rapido logo top-left; map fills the middle; bottom white card shows current location and a "Where to?" / "Enter destination" search bar — tap it to begin booking.
- Booking entry: tapping the destination bar opens a two-field screen. TOP field = pick-up location (auto-filled with GPS). BOTTOM field = destination.
- Location fields: placeholder text ("Pick-up location", "Where to?", "Current location") are labels only — never type them. Tap the text area to focus; a cursor must appear before typing.
- Clear / X icon: the × inside a field clears it — it does NOT confirm selection. Always pick a suggestion from the dropdown after typing.
- Suggestion dropdown: 3–6 results appear below the active field while typing. Tap one to confirm; never skip this step.
- Confirm pickup map: may appear after destination — shows a pin on the map with "Confirm pickup" button. Verify the address matches, then tap the button. If wrong, go back and re-enter.
- Ride options screen: shows horizontal tabs (Bike, Auto, Cab) or a scrollable vertical list. Each card has vehicle icon, fare (Rs), and captain ETA.
- Bike tab: Rapido's signature offering; cheapest and fastest for short distances.
- Auto tab: 3-wheeled auto-rickshaw option; slightly pricier than bike.
- Cab tab: AC car option (availability varies by city).
- Booking confirmation: tap the yellow/orange "Request" or "Book" button at the bottom. A captain-finding screen follows.
- Payment: Rapido shows UPI and cash options AFTER captain accepts — STOP before any PIN entry.
- Popups to dismiss: "Enable notifications", "Rate Rapido", safety tip sheets — use "Later", "Skip", "Dismiss", or the × button.
""".trimIndent(),
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase() ?: "book"
        val destination = params["destination"] as? String ?: ""
        val pickup = LocationRequestNormalizer.normalizePickup(params["pickup"] as? String)
        val transport = (params["transport"] as? String)?.lowercase() ?: "bike"
        val useCurrentPickup = pickup.isBlank()

        if (destination.isBlank()) return SkillResult.Failure("Where do you want to go?")

        runner.openApp("com.rapido.passenger")
        runner.waitForApp("com.rapido.passenger", timeoutMs = 6000)
        delay(800)
        runner.dismissPopups(3)
        delay(200)

        val transportNote = when {
            transport.contains("auto") || transport.contains("rickshaw") ->
                "Choose the Auto option. Ignore Bike and Cab options."
            transport.contains("cab") || transport.contains("car") ->
                "Choose the Cab/Car option if available. Ignore Bike options."
            else ->
                "Choose the Bike option (Rapido's primary offering). Ignore Auto and Cab unless requested."
        }

        val goal = when (action) {
            "book" -> buildRapidoBookingGoal(
                pickup = pickup,
                destination = destination,
                useCurrentPickup = useCurrentPickup,
                transportNote = transportNote,
            )
            "estimate", "price" -> buildRapidoEstimateGoal(
                pickup = pickup,
                destination = destination,
                useCurrentPickup = useCurrentPickup,
                transportNote = transportNote,
            )
            else -> params["goal"] as? String ?: "Do this in Rapido: $action to $destination"
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 28)
        return SkillResult.Success(result)
    }

    private fun buildRapidoBookingGoal(
        pickup: String,
        destination: String,
        useCurrentPickup: Boolean,
        transportNote: String,
    ) = buildString {
        append("You are in the Rapido app. Book a ride")
        if (pickup.isNotBlank()) append(" FROM \"$pickup\"") else append(" FROM the current GPS location")
        append(" TO \"$destination\".\n\n")
        append("RAPIDO UI:\n")
        append("- There are TWO location fields on the booking screen: TOP = pickup, BOTTOM = destination.\n")
        append("- Placeholder text like 'Where to?', 'Pick-up location', 'Current location' are only labels — never type them.\n")
        append("- The tiny X / clear icon inside a field is NOT how you select the field. Tap the field's text area itself.\n\n")
        append("STEPS:\n")
        append("1. Tap the 'Where to?' or destination search bar on the home screen to open the booking flow.\n")
        if (!useCurrentPickup) {
            append("2. On the two-field screen, tap INSIDE the TOP pickup field's text area.\n")
            append("   Type \"$pickup\" and wait for the suggestion dropdown to appear.\n")
            append("   CRITICAL: Always tap one of the dropdown suggestions to confirm pickup. Do NOT tap X or dismiss.\n")
            append("3. Only after pickup suggestion is confirmed, tap INSIDE the BOTTOM destination field.\n")
            append("   Type \"$destination\" and tap the best matching suggestion.\n")
        } else {
            append("2. Leave the TOP pickup field as the current GPS location. If Rapido asks to confirm or use current location, tap Yes / Confirm.\n")
            append("3. Tap INSIDE the BOTTOM destination field. Type \"$destination\" and tap the best matching suggestion.\n")
        }
        append("4. Wait for the ride options screen to load.\n")
        append("5. $transportNote\n")
        append("6. Tap the Book / Request / Confirm button to book the ride.\n")
        append("7. Stop before entering any payment PIN or card details.\n\n")
        append("STRICT RULES:\n")
        append("- Never type a destination into the pickup field or vice versa.\n")
        append("- Always select a suggestion from the dropdown after typing either location.\n")
        append("- If a confirm-pickup map screen appears with the WRONG address, go back and re-enter the correct pickup.\n")
        append("- Do not tap Schedule or any future-booking option. Book for right now.\n")
        append("- Dismiss any promo or notification popups and continue.\n\n")
        append("FINAL REPLY:\n")
        append("Ride booked:\n")
        append("- Pickup: ${if (useCurrentPickup) "Current location" else pickup}\n")
        append("- Destination: $destination\n")
        append("- Type: <ride type>\n")
        append("- Fare: Rs <amount>\n")
        append("- Captain ETA: <eta>")
    }

    private fun buildRapidoEstimateGoal(
        pickup: String,
        destination: String,
        useCurrentPickup: Boolean,
        transportNote: String,
    ) = buildString {
        append("You are in the Rapido app. Get a fare estimate")
        if (pickup.isNotBlank()) append(" from \"$pickup\"") else append(" from current GPS location")
        append(" to \"$destination\".\n\n")
        append("STEPS:\n")
        append("1. Tap the destination field on the home screen.\n")
        if (!useCurrentPickup) {
            append("2. Tap INSIDE the TOP pickup field, type \"$pickup\", and tap the best suggestion.\n")
        } else {
            append("2. Leave the TOP pickup field as current GPS.\n")
        }
        append("3. Tap INSIDE the BOTTOM destination field, type \"$destination\", and tap the best suggestion.\n")
        append("4. Wait for ride options to load. $transportNote\n")
        append("5. Read all visible ride types and fares. Do NOT book.\n\n")
        append("FINAL REPLY:\n")
        append("Rapido estimates:\n")
        append("- <ride type> - Rs <fare> - Captain ETA <eta>\n")
        append("(one per line)")
    }
}
