package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class OlaSkill : Skill {

    override val manifest = SkillManifest(
        id = "ola",
        name = "Ola Cab Booking",
        version = "6.0.0",
        description = "Book cabs, autos, or bikes on Ola with pickup + destination and full ride booking flow",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK, Permission.PAYMENT,
        ),
        allowedPackages = setOf("com.olacabs.customer"),
        exampleParamsHint = """{"destination":"Mumbai Airport","action":"book","pickup":"Bandra"}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase() ?: "book"
        val destination = params["destination"] as? String ?: ""
        val pickup = LocationRequestNormalizer.normalizePickup(params["pickup"] as? String)
        val transport = (params["transport"] as? String)?.lowercase() ?: "cab"
        val useCurrentPickup = pickup.isBlank()

        if (destination.isBlank()) return SkillResult.Failure("Where do you want to go?")

        runner.openApp("com.olacabs.customer")
        runner.waitForApp("com.olacabs.customer", timeoutMs = 6000)
        delay(800)
        runner.dismissPopups(3)
        delay(200)

        val transportNote = when {
            transport.contains("auto") || transport.contains("rickshaw") ->
                "Choose an Auto/Rickshaw option only. Ignore Mini, Prime, Bike, Rental, and Outstation options."
            transport.contains("bike") || transport.contains("moto") ->
                "Choose a Bike option only. Ignore Auto, Mini, Prime, Rental, and Outstation options."
            else ->
                "Choose a car/cab option such as Mini, Prime Sedan, or Sedan. Ignore Auto, Bike, Rental, and Outstation options."
        }

        val goal = when (action) {
            "book" -> buildOlaBookingGoal(
                pickup = pickup,
                destination = destination,
                useCurrentPickup = useCurrentPickup,
                transportNote = transportNote,
            )
            "estimate", "price" -> buildOlaEstimateGoal(
                pickup = pickup,
                destination = destination,
                useCurrentPickup = useCurrentPickup,
                transportNote = transportNote,
            )
            else -> params["goal"] as? String ?: "Do this in Ola: $action to $destination"
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 30)
        return SkillResult.Success(result)
    }

    private fun buildOlaBookingGoal(
        pickup: String,
        destination: String,
        useCurrentPickup: Boolean,
        transportNote: String,
    ) = buildString {
        append("You are in the Ola app. Book a ride")
        if (pickup.isNotBlank()) append(" FROM \"$pickup\"") else append(" FROM the current GPS location")
        append(" TO \"$destination\".\n\n")
        append("OLA HAS TWO LOCATION FIELDS:\n")
        append("- TOP field = pickup location (where the driver picks you up).\n")
        append("- BOTTOM field = destination (where you want to go).\n")
        append("- Placeholder text like 'Where to?', 'Enter pickup location', 'Current location' are only labels — never type them.\n")
        append("- The tiny X / clear icon inside a field is NOT how you select it. Tap the field's text area itself.\n\n")
        append("STEPS:\n")
        append("1. Tap the 'Where to?' or destination search field on the Ola home screen.\n")
        if (!useCurrentPickup) {
            append("2. On the two-field picker, tap INSIDE the TOP pickup field's text area (not the X icon).\n")
            append("   Type \"$pickup\" and wait for the suggestion dropdown.\n")
            append("   CRITICAL: Tap one of the dropdown suggestions to confirm pickup. Do NOT tap X or dismiss.\n")
            append("3. Only after pickup is confirmed, tap INSIDE the BOTTOM destination field.\n")
            append("   Type \"$destination\" and tap the best matching suggestion.\n")
        } else {
            append("2. Leave the TOP pickup field as Ola's current GPS location. If Ola asks to confirm current location, tap Yes / Confirm.\n")
            append("3. Tap INSIDE the BOTTOM destination field. Type \"$destination\" and tap the best matching suggestion.\n")
        }
        append("4. Wait for ride options to load.\n")
        append("5. $transportNote\n")
        append("6. Tap Book or Confirm Booking to book the ride.\n")
        append("7. Stop before entering any payment PIN or card details.\n\n")
        append("STRICT RULES:\n")
        append("- Never type destination into pickup field or vice versa.\n")
        append("- Always select a suggestion from the dropdown after typing either location.\n")
        append("- If a confirm-pickup map screen shows the WRONG address, tap back and re-enter the correct pickup.\n")
        append("- Do not tap Schedule or Outstation. Book for right now.\n")
        append("- Dismiss any promo or notification popups and continue.\n")
        append("- Do not enter payment PIN or card details.\n\n")
        append("FINAL REPLY:\n")
        append("Ride booked:\n")
        append("- Pickup: ${if (useCurrentPickup) "Current location" else pickup}\n")
        append("- Destination: $destination\n")
        append("- Ride: <ride type>\n")
        append("- Fare: Rs <amount>\n")
        append("- Driver ETA: <eta>")
    }

    private fun buildOlaEstimateGoal(
        pickup: String,
        destination: String,
        useCurrentPickup: Boolean,
        transportNote: String,
    ) = buildString {
        append("You are in the Ola app. Get a fare estimate")
        if (pickup.isNotBlank()) append(" from \"$pickup\"") else append(" from current GPS location")
        append(" to \"$destination\".\n\n")
        append("STEPS:\n")
        append("1. Tap the 'Where to?' or destination search field.\n")
        if (!useCurrentPickup) {
            append("2. On the two-field picker, tap INSIDE the TOP pickup field, type \"$pickup\", and tap the best suggestion.\n")
        } else {
            append("2. Leave the TOP pickup as current GPS.\n")
        }
        append("3. Tap INSIDE the BOTTOM destination field, type \"$destination\", tap the best suggestion.\n")
        append("4. Wait for ride options to load. $transportNote\n")
        append("5. Read all visible ride types and fares. Do NOT book.\n\n")
        append("FINAL REPLY:\n")
        append("Ola estimates:\n")
        append("- <ride type> - Rs <fare> - Driver ETA <eta>\n")
        append("(one per line)")
    }
}
