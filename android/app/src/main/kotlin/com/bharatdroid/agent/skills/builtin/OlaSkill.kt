package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class OlaSkill : Skill {

    override val manifest = SkillManifest(
        id = "ola",
        name = "Ola Cab Booking",
        version = "6.0.0",
        description = "Book cabs, check prices, track driver, cancel ride on Ola",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK, Permission.PAYMENT,
        ),
        allowedPackages = setOf("com.olacabs.customer"),
        exampleParamsHint = """{"destination": "Mumbai Airport T2", "action": "book", "rideType": "mini"}""",
        uiKnowledge = """
Ola Cabs UI guide:
- Home screen: map fills most of the screen; "Where are you going?" or destination search bar at the bottom or center
- Destination entry: two stacked text fields — FROM (current location, top) and TO (destination, below) — each with a pin icon
- Ride type cards: horizontal scrolling row after destination is set — Ola Mini, Ola Sedan, Ola Prime, Ola Auto — each shows fare, capacity, ETA
- Book button: large orange button at the very bottom reading "Book Ola [type]" (e.g. "Book Ola Mini")
- Searching for driver: spinner with "Looking for nearby drivers" message
- Driver card: after booking — driver photo, name, star rating, vehicle number, ETA countdown, "Cancel ride" option
- Ride tracking: map shows driver's real-time position, ETA updates live
- Promo/coupon: below ride cards, "Have a promo code?" expandable row
- Active ride: home screen shows current ride status if a ride is active — driver info, "Cancel" button
""".trimIndent(),
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val destination = params["destination"] as? String
        val action = (params["action"] as? String)?.lowercase() ?: "book"
        val rideType = (params["rideType"] as? String)?.lowercase() ?: "mini"

        runner.openApp("com.olacabs.customer")
        runner.waitForApp("com.olacabs.customer", timeoutMs = 7000)
        delay(800)
        runner.dismissPopups(2)
        delay(200)

        // Pre-type destination into search field
        val searchDone = if (destination != null && action in setOf("book", "estimate", "price")) {
            val typed = runner.typeInFieldWithHint("Where are you going", destination)
                || runner.typeInFieldWithHint("Where to", destination)
                || runner.typeInFieldWithHint("Search", destination)
            if (typed) {
                delay(1200)
                true
            } else {
                // Tap the bottom search bar
                val (w, h) = runner.getScreenSize()
                runner.tapAtPoint(w * 0.5f, h * 0.75f)
                delay(400)
                runner.typeReliably(destination)
                delay(1200)
                true
            }
        } else false

        val goal = when (action) {
            "estimate", "price" -> {
                if (destination == null) return SkillResult.Failure("Where do you want to go?")
                """You are in Ola. ${if (searchDone) "Destination \"$destination\" is typed or suggestions are showing." else "Check fare to \"$destination\"."}
STEPS:
${if (!searchDone) "1. Tap the destination search field\n2. Type \"$destination\"\n3. Tap the correct suggestion\n4." else "1. Tap the correct suggestion for \"$destination\"\n2."} Wait for the ride options to load
${if (searchDone) "3." else "5."} Read all the ride types — Ola Mini, Sedan, Prime, Auto — with their fare estimates and ETAs
${if (searchDone) "4." else "6."} Report the prices and ETAs for each ride type
⚠️ Do NOT tap Book"""
            }

            "book" -> {
                if (destination == null) return SkillResult.Failure("Where do you want to go?")
                val rideLabel = rideType.replaceFirstChar { it.uppercase() }
                """You are in Ola. Book a $rideLabel cab to "$destination".
STEPS:
${if (!searchDone) "1. Tap the destination field\n2. Type \"$destination\"\n3. Tap the correct suggestion\n4." else "1. Tap the correct suggestion for \"$destination\"\n2."} Wait for the ride options cards to appear
${if (searchDone) "3." else "5."} Tap the "$rideLabel" (or "Ola $rideLabel") ride card to select it
${if (searchDone) "4." else "6."} Tap the orange "Book Ola $rideLabel" button at the bottom
${if (searchDone) "5." else "7."} Wait for driver search — this may take 30-60 seconds
${if (searchDone) "6." else "8."} Report: driver name, vehicle number, ETA, and fare — or report "Searching for driver" if still waiting"""
            }

            "cancel" ->
                """You are in Ola. Cancel the current ride.
STEPS:
1. Look for the active ride screen — if on home screen, the ride status should be visible
2. Find and tap the "Cancel ride" or "Cancel" button
3. A reason picker may appear — tap any reason (e.g. "Changed my plans")
4. Confirm the cancellation
5. Report: ride has been cancelled or the cancellation fee (if any) shown"""

            "status" ->
                """You are in Ola. Check the status of the current ride.
STEPS:
1. Look at the home screen or the active ride screen
2. Read the driver information — name, rating, vehicle, ETA countdown
3. Report: driver name, vehicle number, current ETA, and ride status"""

            else ->
                if (destination != null)
                    params["goal"] as? String ?: "Do this in Ola: $action to $destination"
                else
                    params["goal"] as? String ?: "Do this in Ola: $action"
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 22)
        return SkillResult.Success(result)
    }
}
