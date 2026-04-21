package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.*
import kotlinx.coroutines.delay

class OlaSkill : Skill {

    override val manifest = SkillManifest(
        id = "ola",
        name = "Ola Cab Booking",
        version = "5.0.0",
        description = "Book cabs on Ola, check ride estimates, track driver",
        author = "bharatdroid-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP, Permission.READ_SCREEN,
            Permission.TAP, Permission.TYPE, Permission.SCROLL,
            Permission.NAVIGATE_BACK, Permission.PAYMENT,
        ),
        allowedPackages = setOf("com.olacabs.customer"),
        exampleParamsHint = """{"destination": "Mumbai Airport", "action": "book"}""",
        uiKnowledge = """
Ola Cabs UI guide:
- Home screen: map fills most of the screen; "Where are you going?" or "Pick-up / Drop" search bar at the bottom
- Destination entry: two stacked text fields — FROM (current location, top) and TO (destination, below it) — each with a pin icon on the left
- Ride type cards: horizontal scrolling row after destination is set — Ola Mini, Ola Sedan, Ola Prime, Ola Auto — each card shows vehicle type, capacity, estimated fare, and ETA
- Book button: large orange button at the very bottom reading "Book Ola [type]" (e.g. "Book Ola Mini")
- Searching for driver: spinner animation with "Looking for nearby drivers" message
- Driver card: appears after booking — shows driver photo, name, star rating, vehicle number, ETA countdown, and "Cancel ride" option
- Promo/coupon field: below ride cards, "Have a promo code?" expandable row
""".trimIndent(),
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val destination = params["destination"] as? String
            ?: return SkillResult.Failure("Where do you want to go?")
        val action = (params["action"] as? String)?.lowercase() ?: "book"

        runner.openApp("com.olacabs.customer")
        runner.waitForApp("com.olacabs.customer", timeoutMs = 6000)
        delay(800)
        runner.dismissPopups(2)
        delay(200)

        val goal = when (action) {
            "estimate", "price" ->
                """You are in Ola cab app. Check price estimate to "$destination".
                STEPS: 1) Tap 'Where to?' or the search/destination field. 2) Type "$destination". 3) Tap the correct suggestion. 4) Read out the ride options and prices shown."""

            "book" ->
                """You are in Ola cab app. Book a cab to "$destination".
                STEPS: 1) Tap 'Where to?' or destination search field. 2) Type "$destination". 3) Tap the correct suggestion. 4) Wait for ride options to load. 5) Tap the most suitable cab option (Mini or Prime). 6) Tap 'Book' or 'Confirm Booking'."""

            else ->
                params["goal"] as? String ?: "Do this in Ola: $action to $destination"
        }

        val result = agent.executeGoal(runner, goal, maxSteps = 20)
        return SkillResult.Success(result)
    }
}
