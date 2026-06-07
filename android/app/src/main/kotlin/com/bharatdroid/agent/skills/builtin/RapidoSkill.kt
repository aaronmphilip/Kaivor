package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class RapidoSkill : Skill {

    override val manifest = SkillManifest(
        id = "rapido",
        name = "Rapido Bike/Auto Booking",
        version = "1.0.0",
        description = "Book bike taxis, autos, or cabs on Rapido with pickup + destination support",
        author = "bharatclaw-team",
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
- ADD A STOP button: there is a "+" or "Add a stop" button between the pickup and destination fields — this adds an INTERMEDIATE WAYPOINT, NOT a pickup location. NEVER tap this button under any circumstances.
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

        val result = agent.executeGoal(runner, goal, maxSteps = 70)
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

        append("--- MANDATORY RULE: HOW TO ENTER ANY LOCATION ---\n")
        append("Every time you type text into a location field:\n")
        append("  a) Tap the field text area — cursor must appear inside it.\n")
        append("  b) Type 5–7 characters of the place name.\n")
        append("  c) STOP. Wait 2 seconds.\n")
        append("  d) A suggestion list appears BELOW the field — tap the best matching place name.\n")
        append("  e) The field text changes to the selected place and the list disappears — confirmed.\n")
        append("  If NO suggestions appear after 2 seconds: type 2–3 more characters and wait again.\n")
        append("NEVER skip tapping a suggestion. NEVER press Enter or continue typing past suggestions.\n")
        append("NEVER tap the × icon to select — it only clears the field.\n\n")

        append("------ CRITICAL WARNING: DO NOT ADD A STOP ------\n")
        append("Between the pickup and destination fields there is a '+ Add a stop' or '+' button.\n")
        append("Tapping it adds an INTERMEDIATE WAYPOINT — it does NOT set the pickup location.\n")
        append("NEVER tap '+ Add stop', 'Add a stop', or any '+' button between the two fields.\n")
        append("If you accidentally tapped it, tap the × on that stop immediately and redo.\n\n")

        append("-- HOW RAPIDO'S LOCATION PICKER WORKS --\n")
        append("Tapping the destination bar opens a sheet with TWO fields:\n")
        append("  TOP field    = PICKUP      — placeholder: 'Pick-up location', 'Current location'\n")
        append("  BOTTOM field = DESTINATION — placeholder: 'Where to?', 'Enter destination'\n")
        append("The BOTTOM (destination) field is AUTO-FOCUSED when the sheet opens.\n\n")

        append("-- STEPS --\n")
        append("1. Tap 'Where to?' or the destination bar on the Rapido home screen.\n")
        append("   ? Location picker opens. BOTTOM destination field is already focused.\n")
        append("2. TYPE DESTINATION FIRST (already focused): type \"$destination\" (5–7 chars).\n")
        append("   ? Wait 2 sec for suggestions, then TAP the best match for \"$destination\".\n")
        if (!useCurrentPickup) {
            append("3. Now tap INSIDE the TOP pickup field's TEXT AREA (NOT the × icon, NOT the '+' between fields).\n")
            append("   ? Cursor must appear inside the TOP field.\n")
            append("   ? If there is existing text, tap and hold to select all, then delete it.\n")
            append("   ? Type \"$pickup\" (5–7 chars). Wait 2 sec. TAP the best suggestion.\n")
        } else {
            append("3. TOP pickup field already shows current GPS. DO NOT tap or clear it.\n")
            append("   If Rapido asks to confirm current location, tap Yes / Confirm.\n")
        }
        append("4. If a 'Confirm pickup' map screen appears:\n")
        append("   ? Verify the pin address matches ${if (useCurrentPickup) "your current location" else "\"$pickup\""}.\n")
        append("   ? If correct, tap 'Confirm pickup' button.\n")
        append("   ? If WRONG address, tap Back and re-enter pickup in step 3.\n")
        append("5. Ride options load. $transportNote\n")
        append("6. Tap Book / Request / Confirm to book.\n")
        append("7. STOP before any payment PIN entry.\n\n")

        append("-- STRICT RULES --\n")
        append("- DESTINATION goes in the BOTTOM field; PICKUP goes in the TOP field. Never swap.\n")
        append("- NEVER tap '+ Add stop' — it creates an intermediate stop, not a pickup.\n")
        append("- Always tap a dropdown suggestion after typing. Never skip.\n")
        append("- Never tap the × icon to focus a field — it only clears.\n")
        append("- Do not type placeholder labels into any field.\n")
        append("- Do not tap Schedule or future-booking options.\n")
        append("- Dismiss promo/notification popups and continue.\n")
        append("- Do not enter payment PIN.\n\n")

        append("FINAL REPLY:\n")
        append("Ride booked:\n")
        append("- Pickup: ${if (useCurrentPickup) "Current location" else pickup}\n")
        append("- Destination: $destination\n")
        append("- Type: <ride type>\n")
        append("- Fare: ?<amount>\n")
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
        append("-- DO NOT ADD A STOP: never tap '+ Add stop' between the two fields --\n\n")
        append("STEPS:\n")
        append("1. Tap the destination bar on the home screen.\n")
        append("   ? BOTTOM destination field is auto-focused.\n")
        append("2. Type \"$destination\" (5–7 chars, already focused). Wait 2 sec. Tap best suggestion.\n")
        if (!useCurrentPickup) {
            append("3. Tap INSIDE the TOP pickup text area (NOT '+ Add stop'). Type \"$pickup\" (5–7 chars). Tap best suggestion.\n")
        } else {
            append("3. Leave TOP pickup as current GPS — do not touch it.\n")
        }
        append("4. Wait for ride options to load. Scroll to see all options. $transportNote\n")
        append("5. READ all visible fares and ETAs from the screen. Do NOT book.\n\n")
        append("FINAL REPLY — read EXACT values from the screen, do NOT use placeholders:\n")
        append("Rapido fares from ${if (useCurrentPickup) "current location" else pickup} to $destination:\n")
        append("- [ride type]: ?[exact fare] — Captain ETA [exact ETA]   ? one line per option\n")
        append("Read every price and ETA from the screen. Do NOT invent numbers.")
    }
}
