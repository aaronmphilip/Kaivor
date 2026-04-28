package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class UberSkill : Skill {

    override val manifest = SkillManifest(
        id = "uber",
        name = "Uber Cab Booking",
        version = "7.0.0",
        description = "Book cabs on Uber with pickup + destination, check fares/ETAs, and support the full ride booking flow",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK, Permission.PAYMENT,
        ),
        allowedPackages = setOf("com.ubercab"),
        exampleParamsHint = """{"action":"book","pickup":"Indiranagar","destination":"Bangalore Airport","ride_type":"UberGo"}""",
        uiKnowledge = "Uber home screen has a 'Where to?' search bar in the center. Tapping it opens location picker with TWO fields: TOP = PICKUP, BOTTOM = DESTINATION (auto-focused). After typing any location, a suggestion list MUST be tapped — never skip it. After locations confirmed, ride options screen shows UberGo, Premier, Auto etc. with fares and ETAs. Tap the desired ride type, then Confirm/Request. Never enter payment PIN.",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val action = (params["action"] as? String)?.lowercase() ?: "book"
        val destination = params["destination"] as? String ?: ""
        val pickup = LocationRequestNormalizer.normalizePickup(params["pickup"] as? String)
        val rideType = (params["ride_type"] as? String ?: params["type"] as? String ?: "").trim()
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
            "book"     -> buildUberGoal(pickup, destination, useCurrentPickup, rideType, book = true)
            "estimate" -> buildUberGoal(pickup, destination, useCurrentPickup, rideType, book = false)
            else       -> params["goal"] as? String ?: "Do this in Uber: $action $destination".trim()
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 36)
        return SkillResult.Success(result)
    }

    private fun buildUberGoal(
        pickup: String,
        destination: String,
        useCurrentPickup: Boolean,
        rideType: String,
        book: Boolean,
    ) = buildString {
        append("You are in the Uber app. ")
        append(if (book) "Book a ride" else "Get a fare estimate")
        if (pickup.isNotBlank()) append(" FROM \"$pickup\"") else append(" FROM current GPS location")
        append(" TO \"$destination\"")
        if (rideType.isNotBlank()) append(" using \"$rideType\"")
        append(".\n\n")

        // ── Suggestion-tapping protocol (most critical instruction) ──────────
        append("═══ MANDATORY RULE: HOW TO ENTER ANY LOCATION ═══\n")
        append("Every time you type text into a location field, you MUST follow these EXACT steps:\n")
        append("  a) Tap the correct input field so the cursor appears inside it.\n")
        append("  b) Type 4–8 characters of the location name (NOT the full address).\n")
        append("  c) STOP typing. Wait 1–2 seconds.\n")
        append("  d) A SUGGESTION LIST appears BELOW the text field — a scrollable list of place names.\n")
        append("     In the element list they are clickable text items directly below the active field.\n")
        append("  e) TAP the suggestion that best matches your target location.\n")
        append("  f) The field text changes to the selected suggestion and the list disappears.\n")
        append("     ONLY THEN is the location confirmed.\n")
        append("NEVER: press Enter, press Search, continue typing past the first suggestions, or proceed without tapping a suggestion.\n")
        append("NEVER: tap the × / clear button to 'select' something — it only clears the field.\n\n")

        // ── Uber location picker layout ──────────────────────────────────────
        append("── HOW UBER'S LOCATION PICKER WORKS ──\n")
        append("Tapping 'Where to?' opens a sheet with TWO input fields:\n")
        append("  TOP field    = PICKUP      (placeholder: 'Current location' or 'Choose starting point')\n")
        append("  BOTTOM field = DESTINATION (placeholder: 'Where to?' or 'Enter destination')\n")
        append("The BOTTOM field is AUTO-FOCUSED when the sheet opens — keyboard is already up for it.\n\n")

        // ── Steps ────────────────────────────────────────────────────────────
        append("── STEPS ──\n")
        append("1. Tap the 'Where to?' black pill button on the Uber home screen.\n")
        append("   → Location picker opens. The BOTTOM destination field is focused (keyboard visible).\n")

        append("2. TYPE DESTINATION (BOTTOM field, already focused):\n")
        append("   → Type \"$destination\" (just the first 5–7 characters is fine).\n")
        append("   → Wait for the suggestion list to appear below.\n")
        append("   → TAP the best matching suggestion for \"$destination\".\n")
        append("   → Destination is now confirmed — its text changes to the selected place.\n")

        if (!useCurrentPickup) {
            append("3. SET PICKUP (TOP field):\n")
            append("   → Tap INSIDE the TOP pickup field's text area — the cursor must move to it.\n")
            append("   → If there's existing text, tap the × icon inside the field to clear it (the × clears, not focuses).\n")
            append("     OR tap and hold the existing text → Select All → Delete.\n")
            append("   → Type \"$pickup\" (first 5–7 characters).\n")
            append("   → Wait for the suggestion list. TAP the best matching suggestion for \"$pickup\".\n")
            append("   → Pickup is now confirmed.\n")
        } else {
            append("3. PICKUP: The TOP field already shows your current GPS location — DO NOT tap it, DO NOT clear it.\n")
        }

        append("4. CONFIRM PICKUP MAP (may appear after both fields are set):\n")
        append("   → If a map screen appears with a draggable pin and a 'Confirm pickup' button:\n")
        append("   → Check that the address label matches ${if (useCurrentPickup) "your current GPS location" else "\"$pickup\""}.\n")
        append("   → If it matches, tap 'Confirm pickup'.\n")
        append("   → If it is WRONG, tap Back and re-enter the correct pickup in step 3.\n")

        append("5. RIDE OPTIONS SCREEN (loads after both locations confirmed):\n")
        append("   → You will see a list of ride options: UberGo, Premier, Uber XL, Auto, Moto, etc.\n")
        append("   → Each option shows: vehicle name, estimated fare (₹XXX), and ETA (X min).\n")
        append("   → SCROLL DOWN if needed to see all options.\n")
        if (rideType.isNotBlank()) {
            append("   → FIND the \"$rideType\" option specifically — scroll until you see it.\n")
            append("   → READ the EXACT fare and ETA shown next to \"$rideType\".\n")
        } else {
            append("   → READ the fare and ETA of each visible option.\n")
        }

        if (book) {
            val targetRide = if (rideType.isNotBlank()) "\"$rideType\"" else "the cheapest available option (usually UberGo or Auto)"
            append("6. TAP $targetRide to select it.\n")
            append("7. Tap 'Choose [ride type]' or 'Confirm' or 'Request [ride type]' to book.\n")
            append("8. STOP — do NOT enter any payment PIN or card details.\n")
        } else {
            append("6. READ ALL visible ride options with their exact fares and ETAs from the screen.\n")
            append("   Do NOT tap Confirm, Request, or Book — this is ESTIMATE ONLY.\n")
        }

        append("\n── STRICT RULES ──\n")
        append("- BOTTOM field = DESTINATION. TOP field = PICKUP. NEVER swap them.\n")
        append("- ALWAYS tap a suggestion from the dropdown after typing. Skipping causes wrong location.\n")
        append("- NEVER tap the × / clear icon to 'select' or 'focus' — it only clears the field.\n")
        append("- Do NOT type placeholder labels like 'Where to?' or 'Current location' into fields.\n")
        append("- If the confirm-pickup map shows a wrong address, go back — do not proceed.\n")
        append("- Do not tap Schedule or Reserve. Book for right now only.\n")
        append("- Dismiss any promo or rating popup and continue.\n")
        append("- Never enter payment PIN or card details.\n\n")

        if (book) {
            val rideLabel = if (rideType.isNotBlank()) rideType else "<ride type shown on screen>"
            append("FINAL REPLY — read EXACT values from the current screen, do NOT use placeholders:\n")
            append("Ride booked ✅\n")
            append("- Pickup: ${if (useCurrentPickup) "Current location" else pickup}\n")
            append("- Destination: $destination\n")
            append("- Ride: $rideLabel\n")
            append("- Fare: ₹[read exact price from screen]\n")
            append("- Pickup ETA: [read exact ETA from screen, e.g. '3 min']\n")
        } else {
            append("FINAL REPLY — read EXACT values from the current screen, do NOT use placeholders:\n")
            append("Uber fares from ${if (useCurrentPickup) "your current location" else pickup} to $destination:\n")
            if (rideType.isNotBlank()) {
                append("$rideType: ₹[exact fare from screen] — ETA [exact ETA from screen]\n")
                append("(also list any other visible options below)\n")
            } else {
                append("- [ride type]: ₹[exact fare] — ETA [exact ETA]   ← one line per option\n")
            }
            append("Read every price and ETA directly from the screen. Do NOT invent or estimate numbers.")
        }
    }
}
