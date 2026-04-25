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

        if (action == "book" && destination.isBlank())
            return SkillResult.Failure("Where do you want to go?")
        if (action == "estimate" && destination.isBlank())
            return SkillResult.Failure("Where do you want an estimate for?")

        val goal = when (action) {
            "book" -> buildUberGoal(pickup, destination, useCurrentPickup, book = true)
            "estimate" -> buildUberGoal(pickup, destination, useCurrentPickup, book = false)
            else -> params["goal"] as? String ?: "Do this in Uber: $action $destination".trim()
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 32)
        return SkillResult.Success(result)
    }

    private fun buildUberGoal(
        pickup: String,
        destination: String,
        useCurrentPickup: Boolean,
        book: Boolean,
    ) = buildString {
        append("You are in the Uber app. ")
        append(if (book) "Book a ride" else "Get a fare estimate")
        if (pickup.isNotBlank()) append(" FROM \"$pickup\"") else append(" FROM current GPS location")
        append(" TO \"$destination\".\n\n")

        append("── HOW UBER'S LOCATION PICKER WORKS ──\n")
        append("When you tap 'Where to?' a sheet opens with TWO fields:\n")
        append("  TOP field    = PICKUP   — placeholder: 'Enter pickup location', 'Current location', 'Choose starting point'\n")
        append("  BOTTOM field = DESTINATION — placeholder: 'Where to?', 'Enter destination', 'Search destination'\n")
        append("The BOTTOM (destination) field is AUTO-FOCUSED when the sheet opens — the keyboard is already for it.\n")
        append("The TINY X / clear icon inside a field CLEARS it — tapping X does NOT confirm or focus a field.\n")
        append("After typing in any field, a suggestion dropdown appears below it. You MUST tap a suggestion — never skip this.\n\n")

        append("── STEPS ──\n")
        append("1. Tap the 'Where to?' black pill button on the Uber home screen.\n")
        append("   → The location picker opens. The BOTTOM destination field is already focused (keyboard visible).\n")
        append("2. TYPE DESTINATION FIRST (it's already focused): type \"$destination\".\n")
        append("   → A suggestion list appears. Tap the best matching result for \"$destination\".\n")
        append("   → The destination field is now confirmed.\n")
        if (!useCurrentPickup) {
            append("3. NOW handle pickup: tap INSIDE the TOP pickup field's text area.\n")
            append("   → A cursor must appear in the TOP field before you type.\n")
            append("   → If the field already has text (e.g. current GPS address), clear it: tap and hold the text, select all, then delete.\n")
            append("   → Type \"$pickup\".\n")
            append("   → A suggestion list appears. Tap the best matching result for \"$pickup\".\n")
            append("   → The pickup field is now confirmed.\n")
        } else {
            append("3. Pickup: the TOP field already shows your current GPS location. DO NOT tap it, DO NOT clear it.\n")
        }
        append("4. After both fields are filled, Uber may show a 'Confirm pickup' map screen.\n")
        append("   → The map shows a pin with an address label.\n")
        append("   → If the address matches ${if (useCurrentPickup) "your current location" else "\"$pickup\""}, tap 'Confirm pickup'.\n")
        append("   → If it shows the WRONG address, tap Back and re-enter the correct pickup in step 3.\n")
        append("5. The ride options screen loads — shows Uber Go, Premier, Auto, etc. with fares and ETAs.\n")
        if (book) {
            append("6. Tap the cheapest option (usually Uber Go or Auto) unless the user asked for a specific type.\n")
            append("7. Tap 'Choose <ride type>' or 'Confirm' or 'Request <ride type>' to book.\n")
            append("8. STOP before any payment PIN entry.\n")
        } else {
            append("6. READ all visible ride types and fares. Do NOT tap Confirm or Request — estimate only.\n")
        }

        append("\n── STRICT RULES ──\n")
        append("- The BOTTOM field is for DESTINATION. The TOP field is for PICKUP. Never swap them.\n")
        append("- ALWAYS tap a suggestion from the dropdown after typing. Never skip the suggestion step.\n")
        append("- NEVER tap the X / clear icon to 'select' or 'focus' a field — it only clears.\n")
        append("- Do NOT type placeholder labels like 'Where to?' or 'Current location' into any field.\n")
        append("- If the confirm-pickup map shows the wrong address, go back — do not proceed with a wrong pickup.\n")
        append("- Do not tap Schedule or Reserve. Book for right now only.\n")
        append("- Dismiss any promo or rating popup and continue.\n")
        append("- Never enter payment PIN or card details.\n\n")

        if (book) {
            append("FINAL REPLY:\n")
            append("Ride booked:\n")
            append("- Pickup: ${if (useCurrentPickup) "Current location" else pickup}\n")
            append("- Destination: $destination\n")
            append("- Ride: <ride type>\n")
            append("- Fare: ₹<amount>\n")
            append("- Pickup ETA: <eta>")
        } else {
            append("FINAL REPLY:\n")
            append("Uber estimates from ${if (useCurrentPickup) "current location" else pickup} to $destination:\n")
            append("- <ride type> — ₹<fare> — ETA <eta>\n")
            append("(one ride per line, do not merge)")
        }
    }
}
