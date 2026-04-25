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
        uiKnowledge = """
Ola app UI guide:
- Home screen: Ola logo top-left; bottom half shows a map with current GPS pin; a white card at the bottom has a "Where to?" search bar — tap it to start booking.
- Booking entry: tapping "Where to?" opens a two-field screen. TOP field = pickup (pre-filled with current location or last address). BOTTOM field = destination (labelled "Where to?" or "Enter destination").
- Location fields: both fields show placeholder text ("Enter pickup location", "Current location", "Where to?") — these are NOT content, do NOT type them. Tap the field's text area itself to focus it; a blinking cursor must appear before typing.
- Clear / X icon: a small × appears inside a filled field — tapping it CLEARS the field, it does NOT confirm selection. Always pick from the dropdown suggestion list after typing.
- Suggestion dropdown: appears below the active field as you type; shows 3–6 place names. ALWAYS tap one suggestion to confirm the location; never proceed without selecting.
- Confirm pickup map screen: after destination is set Ola may show a full-screen map with a draggable pin asking to "Confirm pickup location". Verify the address label matches the intended pickup before tapping the green "Confirm Pickup" button; if wrong, tap back and re-enter.
- Ride options list: scrollable vertical list of ride types — Mini, Prime Sedan, Prime SUV, Auto, Bike, Rental, Outstation. Each card shows vehicle icon, estimated fare (Rs), and ETA.
- Selecting a ride: tap the ride card; it highlights in orange/green. Then tap the green "Book" or "Confirm Booking" button at the bottom.
- Payment screen: shows UPI, cards, and Ola Money. STOP here — do NOT enter any PIN or card details.
- Promo/notification popups: "Allow notifications", "Rate us", "Try Ola Electric" — dismiss with "Later", "Skip", "Not Now", or the × button.
- Scheduled / Outstation tab: appears at the top of ride options. IGNORE these — book for "Right Now" only.
""".trimIndent(),
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
        if (pickup.isNotBlank()) append(" FROM \"$pickup\"") else append(" FROM current GPS location")
        append(" TO \"$destination\".\n\n")

        append("── HOW OLA'S LOCATION PICKER WORKS ──\n")
        append("Tapping 'Where to?' opens a sheet with TWO fields:\n")
        append("  TOP field    = PICKUP      — placeholder: 'Enter pickup location', 'Current location'\n")
        append("  BOTTOM field = DESTINATION — placeholder: 'Where to?', 'Enter destination'\n")
        append("The BOTTOM (destination) field is AUTO-FOCUSED when the sheet opens.\n")
        append("The X / clear icon clears a field — it does NOT select or focus it.\n")
        append("After typing, always tap a suggestion from the dropdown to confirm.\n\n")

        append("── STEPS ──\n")
        append("1. Tap 'Where to?' on the Ola home screen.\n")
        append("   → Location picker opens. BOTTOM destination field is already focused.\n")
        append("2. TYPE DESTINATION FIRST (already focused): type \"$destination\".\n")
        append("   → Tap the best suggestion from the dropdown.\n")
        if (!useCurrentPickup) {
            append("3. Now tap INSIDE the TOP pickup field's text area.\n")
            append("   → Cursor must appear in the TOP field. Clear any existing text, then type \"$pickup\".\n")
            append("   → Tap the best suggestion from the dropdown to confirm pickup.\n")
        } else {
            append("3. The TOP pickup field already shows current GPS location. DO NOT tap or clear it.\n")
        }
        append("4. If Ola shows a 'Confirm pickup location' map screen:\n")
        append("   → Verify the pin address matches ${if (useCurrentPickup) "your current location" else "\"$pickup\""}.\n")
        append("   → If correct, tap the green 'Confirm Pickup' button.\n")
        append("   → If WRONG address, tap Back and re-enter the pickup in step 3.\n")
        append("5. Ride options load. $transportNote\n")
        append("6. Tap Book or Confirm Booking.\n")
        append("7. STOP before any payment PIN entry.\n\n")

        append("── STRICT RULES ──\n")
        append("- Type DESTINATION in the BOTTOM field, PICKUP in the TOP field. Never swap.\n")
        append("- Always tap a suggestion dropdown item after typing. Never skip.\n")
        append("- Never tap the X icon to focus a field — it only clears.\n")
        append("- Do not type placeholder labels ('Where to?', 'Current location') into fields.\n")
        append("- Do not tap Schedule or Outstation. Book right now only.\n")
        append("- Dismiss promo/notification popups and continue.\n")
        append("- Do not enter payment PIN.\n\n")

        append("FINAL REPLY:\n")
        append("Ride booked:\n")
        append("- Pickup: ${if (useCurrentPickup) "Current location" else pickup}\n")
        append("- Destination: $destination\n")
        append("- Ride: <ride type>\n")
        append("- Fare: ₹<amount>\n")
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
        append("1. Tap 'Where to?' on the home screen.\n")
        append("   → BOTTOM destination field is auto-focused.\n")
        append("2. Type \"$destination\" (already focused). Tap best suggestion.\n")
        if (!useCurrentPickup) {
            append("3. Tap INSIDE the TOP pickup field. Type \"$pickup\". Tap best suggestion.\n")
        } else {
            append("3. Leave TOP pickup as current GPS — do not touch it.\n")
        }
        append("4. Wait for ride options. $transportNote\n")
        append("5. Read all fares. Do NOT book.\n\n")
        append("FINAL REPLY:\n")
        append("Ola estimates:\n")
        append("- <ride type> — ₹<fare> — Driver ETA <eta>\n")
        append("(one per line)")
    }
}
