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
        if (pickup.isNotBlank()) append(" FROM \"$pickup\"") else append(" FROM current GPS location")
        append(" TO \"$destination\".\n\n")

        append("── HOW RAPIDO'S LOCATION PICKER WORKS ──\n")
        append("Tapping the destination bar opens a sheet with TWO fields:\n")
        append("  TOP field    = PICKUP      — placeholder: 'Pick-up location', 'Current location'\n")
        append("  BOTTOM field = DESTINATION — placeholder: 'Where to?', 'Enter destination'\n")
        append("The BOTTOM (destination) field is AUTO-FOCUSED when the sheet opens.\n")
        append("The X / clear icon clears a field — it does NOT select or focus it.\n")
        append("After typing in any field, always tap a suggestion from the dropdown to confirm.\n\n")

        append("── STEPS ──\n")
        append("1. Tap 'Where to?' or the destination bar on the Rapido home screen.\n")
        append("   → Location picker opens. BOTTOM destination field is already focused.\n")
        append("2. TYPE DESTINATION FIRST (already focused): type \"$destination\".\n")
        append("   → Tap the best suggestion from the dropdown to confirm.\n")
        if (!useCurrentPickup) {
            append("3. Now tap INSIDE the TOP pickup field's text area.\n")
            append("   → A cursor must appear in the TOP field. Clear any existing text, then type \"$pickup\".\n")
            append("   → Tap the best suggestion from the dropdown to confirm pickup.\n")
        } else {
            append("3. TOP pickup field already shows current GPS. DO NOT tap or clear it.\n")
            append("   If Rapido asks to confirm current location, tap Yes / Confirm.\n")
        }
        append("4. If a 'Confirm pickup' map screen appears:\n")
        append("   → Verify the pin address matches ${if (useCurrentPickup) "your current location" else "\"$pickup\""}.\n")
        append("   → If correct, tap 'Confirm pickup' button.\n")
        append("   → If WRONG address, tap Back and re-enter pickup in step 3.\n")
        append("5. Ride options load. $transportNote\n")
        append("6. Tap Book / Request / Confirm to book.\n")
        append("7. STOP before any payment PIN entry.\n\n")

        append("── STRICT RULES ──\n")
        append("- Type DESTINATION in BOTTOM field, PICKUP in TOP field. Never swap.\n")
        append("- Always tap a suggestion after typing. Never skip.\n")
        append("- Never use the X icon to focus a field — it only clears.\n")
        append("- Do not type placeholder labels into any field.\n")
        append("- Do not tap Schedule or future-booking options.\n")
        append("- Dismiss promo/notification popups and continue.\n")
        append("- Do not enter payment PIN.\n\n")

        append("FINAL REPLY:\n")
        append("Ride booked:\n")
        append("- Pickup: ${if (useCurrentPickup) "Current location" else pickup}\n")
        append("- Destination: $destination\n")
        append("- Type: <ride type>\n")
        append("- Fare: ₹<amount>\n")
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
        append("1. Tap the destination bar on the home screen.\n")
        append("   → BOTTOM destination field is auto-focused.\n")
        append("2. Type \"$destination\" (already focused). Tap best suggestion.\n")
        if (!useCurrentPickup) {
            append("3. Tap INSIDE the TOP pickup field. Type \"$pickup\". Tap best suggestion.\n")
        } else {
            append("3. Leave TOP pickup as current GPS — do not touch it.\n")
        }
        append("4. Wait for ride options to load. $transportNote\n")
        append("5. Read all fares. Do NOT book.\n\n")
        append("FINAL REPLY:\n")
        append("Rapido estimates:\n")
        append("- <ride type> — ₹<fare> — Captain ETA <eta>\n")
        append("(one per line)")
    }
}
