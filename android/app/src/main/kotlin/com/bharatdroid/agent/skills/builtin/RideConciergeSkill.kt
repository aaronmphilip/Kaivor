package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.ScreenAgent
import com.bharatdroid.agent.skills.Permission
import com.bharatdroid.agent.skills.SandboxedRunner
import com.bharatdroid.agent.skills.Skill
import com.bharatdroid.agent.skills.SkillContext
import com.bharatdroid.agent.skills.SkillManifest
import com.bharatdroid.agent.skills.SkillResult
import kotlinx.coroutines.delay

class RideConciergeSkill : Skill {

    private data class RouteEta(
        val pickupText: String,
        val destinationText: String,
        val text: String,
        val distanceText: String,
        val minutes: Int?,
        val modeLabel: String,
        val raw: String,
    )

    private data class RideEstimate(
        val provider: String,
        val rideType: String,
        val fareRupees: Int?,
        val pickupEtaText: String,
        val raw: String,
    )

    override val manifest = SkillManifest(
        id = "ride_concierge",
        name = "Ride Concierge (Uber/Ola/Rapido + ETA + WhatsApp)",
        version = "2.0.0",
        description = "Compares Uber, Ola, and Rapido or uses the chosen provider, honors an explicit pickup instead of GPS, reads road ETA, books the ride after confirmation, and messages a contact.",
        author = "bharatclaw-team",
        trusted = true,
        permissions = setOf(
            Permission.OPEN_APP,
            Permission.READ_SCREEN,
            Permission.TAP,
            Permission.TYPE,
            Permission.SCROLL,
            Permission.NAVIGATE_BACK,
            Permission.PAYMENT,
            Permission.SENSITIVE_READ,
        ),
        allowedPackages = setOf(
            "com.google.android.apps.maps",
            "com.ubercab",
            "com.olacabs.customer",
            "com.rapido.passenger",
            "com.whatsapp",
        ),
        exampleParamsHint = """{"destination":"Bangalore Airport","pickup":"Indiranagar","via":"uber","transport":"cab","contact":"Mom","message":"I'm leaving"}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val runner = context.runner
        val agent = context.agent ?: return SkillResult.Failure("Agent not available.")

        val destination = firstNonBlank(
            params["destination"] as? String,
            params["to"] as? String,
        ) ?: return SkillResult.Failure("Where do you want to go? (provide 'destination')")

        val pickup = firstNonBlank(
            params["pickup"] as? String,
            params["origin"] as? String,
            params["from"] as? String,
        ).let(LocationRequestNormalizer::normalizePickup)

        val providerPreference = normalizeProvider(
            firstNonBlank(
                params["via"] as? String,
                params["provider"] as? String,
                params["app"] as? String,
            ),
        )
        val transport = normalizeTransport(
            firstNonBlank(
                params["transport"] as? String,
                params["rideType"] as? String,
                params["mode"] as? String,
            ),
        )
        val selectionPreference = normalizeSelectionPreference(params["preference"] as? String)
        val contact = firstNonBlank(
            params["contact"] as? String,
            params["notify"] as? String,
        ).orEmpty()
        val baseMessage = firstNonBlank(
            params["message"] as? String,
            params["text"] as? String,
        ) ?: "I'm leaving"

        val routeEta = readRoadEta(
            runner = runner,
            agent = agent,
            pickup = pickup,
            destination = destination,
            transport = transport,
        )

        val estimates = mutableListOf<RideEstimate>()
        when (providerPreference) {
            "uber" -> estimates += readUberEstimate(runner, agent, pickup, destination, transport)
            "ola" -> estimates += readOlaEstimate(runner, agent, pickup, destination, transport)
            "rapido" -> estimates += readRapidoEstimate(runner, agent, pickup, destination, transport)
            else -> {
                estimates += readUberEstimate(runner, agent, pickup, destination, transport)
                estimates += readOlaEstimate(runner, agent, pickup, destination, transport)
                // Include Rapido only for bike/auto so the comparison stays fast for cab requests
                if (transport == "bike" || transport == "auto") {
                    estimates += readRapidoEstimate(runner, agent, pickup, destination, transport)
                }
            }
        }

        val chosen = chooseRide(estimates, providerPreference, selectionPreference)
            ?: return SkillResult.Success(
                buildString {
                    appendLine("I checked the route but could not reliably choose a ride yet.")
                    appendLine("Route ETA: ${formatRouteEta(routeEta)}")
                    appendLine()
                    appendLine("Ride results:")
                    estimates.forEach { appendLine("- ${it.providerLabel()}: ${it.raw.take(220)}") }
                    appendLine()
                    append("Try again with `via Uber` or `via Ola` for a more guided run.")
                },
            )

        val outboundMessage = if (contact.isNotBlank()) {
            buildOutboundMessage(
                baseMessage = baseMessage,
                destination = destination,
                routeEta = routeEta,
                chosen = chosen,
            )
        } else {
            ""
        }

        return SkillResult.NeedsConfirmation(
            prompt = buildConfirmationPrompt(
                destination = destination,
                pickup = pickup,
                routeEta = routeEta,
                estimates = estimates,
                chosen = chosen,
                contact = contact,
                outboundMessage = outboundMessage,
            ),
            onConfirm = confirm@{
                val bookingResult = when (chosen.provider) {
                    "uber" -> bookUberRide(runner, agent, pickup, destination, transport, chosen)
                    "ola" -> bookOlaRide(runner, agent, pickup, destination, transport, chosen)
                    "rapido" -> bookRapidoRide(runner, agent, pickup, destination, transport, chosen)
                    else -> SkillResult.Failure("Unsupported provider: ${chosen.provider}")
                }

                val bookingText = when (bookingResult) {
                    is SkillResult.Success -> bookingResult.message
                    is SkillResult.Failure -> return@confirm bookingResult
                    is SkillResult.NeedsConfirmation -> return@confirm bookingResult
                    is SkillResult.Media -> return@confirm SkillResult.Failure("Ride booking returned media instead of a booking result.")
                }

                val messageText = if (contact.isBlank()) {
                    "No WhatsApp message requested."
                } else {
                    when (
                        val messageResult = WhatsAppSkill().execute(
                            context,
                            mapOf(
                                "action" to "send",
                                "contact" to contact,
                                "message" to outboundMessage,
                                "send" to true,
                            ),
                        )
                    ) {
                        is SkillResult.Success -> messageResult.message
                        is SkillResult.Failure -> "Ride booked, but WhatsApp failed: ${messageResult.reason}"
                        is SkillResult.NeedsConfirmation -> "Ride booked. WhatsApp needs extra confirmation."
                        is SkillResult.Media -> "Ride booked. WhatsApp returned media instead of a message result."
                    }
                }

                SkillResult.Success(
                    buildString {
                        appendLine("Ride flow complete.")
                        appendLine()
                        appendLine("Route")
                        appendLine("- Pickup: ${routeEta.pickupText}")
                        appendLine("- Destination: ${routeEta.destinationText}")
                        appendLine("- Mode: ${routeEta.modeLabel}")
                        appendLine("- ETA: ${routeEta.text}")
                        appendLine("- Distance: ${routeEta.distanceText}")
                        appendLine()
                        appendLine("Chosen Ride")
                        appendLine("- Provider: ${chosen.providerLabel()}")
                        appendLine("- Ride: ${chosen.rideType.ifBlank { "Best matching option" }}")
                        appendLine("- Fare: ${chosen.fareLabel()}")
                        appendLine("- Pickup ETA: ${chosen.pickupEtaText.ifBlank { "unknown" }}")
                        appendLine()
                        appendLine("Booking:")
                        appendLine(bookingText)
                        appendLine()
                        appendLine("WhatsApp:")
                        append(messageText)
                    },
                )
            },
        )
    }

    private suspend fun readRoadEta(
        runner: SandboxedRunner,
        agent: ScreenAgent,
        pickup: String,
        destination: String,
        transport: String,
    ): RouteEta {
        openApp(runner, "com.google.android.apps.maps", dismissCount = 2)

        val modeLabel = mapsModeLabelForTransport(transport)
        val prepareResult = agent.executeGoal(
            runner,
            buildPrepareMapsRouteGoal(
                pickup = pickup,
                destination = destination,
                modeLabel = modeLabel,
            ),
            maxSteps = 50,
        )
        delay(450)

        var readResult = agent.executeGoal(
            runner,
            buildReadMapsRouteGoal(
                pickup = pickup,
                destination = destination,
                modeLabel = modeLabel,
            ),
            maxSteps = 70,
        )

        var pickupText = extractRouteField(readResult, "pickup")
        var destinationText = extractRouteField(readResult, "destination")
        var etaText = extractRouteField(readResult, "eta").ifBlank { extractEtaText(readResult) }
        var distanceText = extractRouteField(readResult, "distance").ifBlank { extractDistanceText(readResult) }

        val needsCorrection = etaText.isBlank() || etaText == "unknown" ||
            (pickup.isNotBlank() && !containsNormalized(pickupText, pickup)) ||
            !containsNormalized(destinationText, destination)

        val correctionResult = if (needsCorrection) {
            delay(350)
            agent.executeGoal(
                runner,
                buildFixMapsRouteGoal(
                    pickup = pickup,
                    destination = destination,
                    modeLabel = modeLabel,
                ),
                maxSteps = 45,
            )
        } else {
            ""
        }

        if (correctionResult.isNotBlank()) {
            delay(450)
            val secondRead = agent.executeGoal(
                runner,
                buildReadMapsRouteGoal(
                    pickup = pickup,
                    destination = destination,
                    modeLabel = modeLabel,
                ),
                maxSteps = 65,
            )

            val secondPickup = extractRouteField(secondRead, "pickup")
            val secondDestination = extractRouteField(secondRead, "destination")
            val secondEta = extractRouteField(secondRead, "eta").ifBlank { extractEtaText(secondRead) }
            val secondDistance = extractRouteField(secondRead, "distance").ifBlank { extractDistanceText(secondRead) }

            if (
                secondEta.isNotBlank() && secondEta != "unknown" ||
                containsNormalized(secondPickup, pickup) ||
                containsNormalized(secondDestination, destination)
            ) {
                readResult = secondRead
                pickupText = secondPickup
                destinationText = secondDestination
                etaText = secondEta
                distanceText = secondDistance
            }
        }

        return RouteEta(
            pickupText = pickupText.ifBlank { pickup.ifBlank { "Current location" } },
            destinationText = destinationText.ifBlank { destination },
            text = etaText.ifBlank { "unknown" },
            distanceText = distanceText.ifBlank { "unknown" },
            minutes = parseDurationMinutes(etaText.ifBlank { readResult }),
            modeLabel = modeLabel,
            raw = buildString {
                appendLine("prepare=$prepareResult")
                appendLine("read=$readResult")
                if (correctionResult.isNotBlank()) append("fix=$correctionResult")
            }.trim(),
        )
    }

    private suspend fun readUberEstimate(
        runner: SandboxedRunner,
        agent: ScreenAgent,
        pickup: String,
        destination: String,
        transport: String,
    ): RideEstimate {
        openApp(runner, "com.ubercab", dismissCount = 3)
        val result = agent.executeGoal(
            runner,
            buildEstimateGoal(
                provider = "uber",
                pickup = pickup,
                destination = destination,
                transport = transport,
            ),
            maxSteps = 65,
        )
        return parseEstimate("uber", result)
    }

    private suspend fun readOlaEstimate(
        runner: SandboxedRunner,
        agent: ScreenAgent,
        pickup: String,
        destination: String,
        transport: String,
    ): RideEstimate {
        openApp(runner, "com.olacabs.customer", dismissCount = 3)
        val result = agent.executeGoal(
            runner,
            buildEstimateGoal(
                provider = "ola",
                pickup = pickup,
                destination = destination,
                transport = transport,
            ),
            maxSteps = 65,
        )
        return parseEstimate("ola", result)
    }

    private suspend fun bookUberRide(
        runner: SandboxedRunner,
        agent: ScreenAgent,
        pickup: String,
        destination: String,
        transport: String,
        chosen: RideEstimate,
    ): SkillResult {
        openApp(runner, "com.ubercab", dismissCount = 3)
        val result = agent.executeGoal(
            runner,
            buildBookingGoal(
                provider = "uber",
                pickup = pickup,
                destination = destination,
                transport = transport,
                chosen = chosen,
            ),
            maxSteps = 80,
        )
        return SkillResult.Success(result)
    }

    private suspend fun bookOlaRide(
        runner: SandboxedRunner,
        agent: ScreenAgent,
        pickup: String,
        destination: String,
        transport: String,
        chosen: RideEstimate,
    ): SkillResult {
        openApp(runner, "com.olacabs.customer", dismissCount = 3)
        val result = agent.executeGoal(
            runner,
            buildBookingGoal(
                provider = "ola",
                pickup = pickup,
                destination = destination,
                transport = transport,
                chosen = chosen,
            ),
            maxSteps = 80,
        )
        return SkillResult.Success(result)
    }

    private suspend fun readRapidoEstimate(
        runner: SandboxedRunner,
        agent: ScreenAgent,
        pickup: String,
        destination: String,
        transport: String,
    ): RideEstimate {
        openApp(runner, "com.rapido.passenger", dismissCount = 3)
        val result = agent.executeGoal(
            runner,
            buildEstimateGoal(
                provider = "rapido",
                pickup = pickup,
                destination = destination,
                transport = transport,
            ),
            maxSteps = 65,
        )
        return parseEstimate("rapido", result)
    }

    private suspend fun bookRapidoRide(
        runner: SandboxedRunner,
        agent: ScreenAgent,
        pickup: String,
        destination: String,
        transport: String,
        chosen: RideEstimate,
    ): SkillResult {
        openApp(runner, "com.rapido.passenger", dismissCount = 3)
        val result = agent.executeGoal(
            runner,
            buildBookingGoal(
                provider = "rapido",
                pickup = pickup,
                destination = destination,
                transport = transport,
                chosen = chosen,
            ),
            maxSteps = 80,
        )
        return SkillResult.Success(result)
    }

    private suspend fun openApp(
        runner: SandboxedRunner,
        packageName: String,
        dismissCount: Int,
    ) {
        runner.openApp(packageName)
        runner.waitForApp(packageName, timeoutMs = 7000)
        delay(800)
        runner.dismissPopups(dismissCount)
        delay(250)
    }

    private fun buildPrepareMapsRouteGoal(
        pickup: String,
        destination: String,
        modeLabel: String,
    ): String = buildString {
        append("You are in Google Maps. Prepare the directions screen for a route to \"$destination\".\n\n")
        append("RULES:\n")
        append("- TOP field on the directions screen = pickup/origin.\n")
        append("- BOTTOM field on the directions screen = destination.\n")
        if (pickup.isNotBlank()) {
            append("- Use \"$pickup\" as the pickup/origin. Do NOT use current location or GPS.\n")
        } else {
            append("- Use the current location as the pickup/origin.\n")
        }
        append("- Use the $modeLabel mode.\n")
        append("- Do NOT start live navigation.\n\n")
        append("STEPS:\n")
        append("1. Tap the search bar and search for \"$destination\".\n")
        append("2. Tap the best matching place.\n")
        append("3. Tap the Directions button.\n")
        if (pickup.isNotBlank()) {
            append("4. On the directions screen, tap the TOP pickup field. Clear the existing location and type \"$pickup\". Tap the best matching suggestion.\n")
        } else {
            append("4. Leave the TOP pickup field as the current location.\n")
        }
        append("5. Verify the BOTTOM destination field contains \"$destination\". If it is blank or wrong, tap it, type \"$destination\", and choose the best suggestion.\n")
        append("6. Wait for the route options to load.\n")
        append("7. Select the $modeLabel tab.\n")
        append("8. Stop on the route screen without pressing Start or Go.\n")
        append("9. Call done EXACTLY like: 'Route ready: pickup=<shown-pickup>, destination=<shown-destination>, mode=$modeLabel'. Use the actual text shown in the fields.\n")
    }

    private fun buildReadMapsRouteGoal(
        pickup: String,
        destination: String,
        modeLabel: String,
    ): String = buildString {
        append("You are already on the Google Maps directions screen. Do NOT navigate anywhere else.\n\n")
        append("READ ONLY. Do not tap Start or Go.\n")
        append("TOP field = pickup/origin.\n")
        append("BOTTOM field = destination.\n")
        append("Expected destination: \"$destination\".\n")
        if (pickup.isNotBlank()) {
            append("Expected pickup: \"$pickup\".\n")
        }
        append("Expected mode: $modeLabel.\n\n")
        append("STEPS:\n")
        append("1. Read the text shown in the TOP pickup field.\n")
        append("2. Read the text shown in the BOTTOM destination field.\n")
        append("3. Read the visible ETA.\n")
        append("4. Read the visible distance.\n")
        append("5. Call done EXACTLY like: 'Maps route: pickup=<pickup>, destination=<destination>, eta=<eta>, distance=<distance>, mode=$modeLabel'. If a value is missing, write unknown.\n")
    }

    private fun buildFixMapsRouteGoal(
        pickup: String,
        destination: String,
        modeLabel: String,
    ): String = buildString {
        append("You are on the Google Maps directions screen, but the route fields are wrong or empty. Fix them now.\n\n")
        append("IMPORTANT:\n")
        append("- TOP field = pickup/origin.\n")
        append("- BOTTOM field = destination.\n")
        append("- Do NOT press Start or Go.\n")
        append("- Stay on the directions screen.\n\n")
        append("STEPS:\n")
        if (pickup.isNotBlank()) {
            append("1. Tap the TOP pickup field, clear the wrong value, type \"$pickup\", and tap the best suggestion.\n")
        } else {
            append("1. Leave the TOP pickup field as the current location.\n")
        }
        append("2. Tap the BOTTOM destination field, clear the wrong value if needed, type \"$destination\", and tap the best suggestion.\n")
        append("3. Wait for the route options to refresh.\n")
        append("4. Select the $modeLabel tab.\n")
        append("5. Call done EXACTLY like: 'Route fixed: pickup=<shown-pickup>, destination=<shown-destination>, mode=$modeLabel'.\n")
    }

    private fun buildEstimateGoal(
        provider: String,
        pickup: String,
        destination: String,
        transport: String,
    ): String = buildString {
        val transportGuidance = transportGuidance(provider, transport)
        val useCurrentPickup = pickup.isBlank()
        append("You are in the ${providerLabel(provider)} app. Read the best estimate from \"${
            pickup.ifBlank { "current location" }
        }\" to \"$destination\".\n\n")
        append("RULES:\n")
        if (!useCurrentPickup) {
            append("- Use \"$pickup\" as the pickup/origin. Do NOT leave it as GPS/current location.\n")
        } else {
            append("- Use the current location as the pickup/origin.\n")
        }
        append("- Placeholder text such as 'Enter pickup location', 'Current location', 'Where to?', or 'Search destination' is only a field label. Never type the label words themselves.\n")
        append("- Never tap the tiny X / clear icon inside the pickup field when you mean to focus or select the field.\n")
        append("- $transportGuidance\n")
        append("- Do NOT book yet.\n\n")
        append("STEPS:\n")
        append("1. Tap the destination or 'Where to?' field.\n")
        append("2. If a two-field location picker appears, the TOP field is pickup and the BOTTOM field is destination.\n")
        if (!useCurrentPickup) {
            append("3. Tap INSIDE the TOP pickup field itself, not the X / clear icon. Type ONLY \"$pickup\" and choose the best pickup suggestion BEFORE touching the destination field.\n")
            append("4. Tap INSIDE the BOTTOM destination field. Use the placeholder only to identify the field, then type ONLY \"$destination\" and choose the best suggestion.\n")
        } else {
            append("3. Leave the pickup field on the app's current-location / GPS value. If the app asks to use or confirm the current location, choose that option and do not erase the pickup.\n")
            append("4. Tap INSIDE the destination field. Use the placeholder only to identify the field, then type ONLY \"$destination\" and choose the best suggestion.\n")
        }
        append("5. Wait for the ride options and fares to load.\n")
        append("6. Find the cheapest matching option according to the transport rule above.\n")
        append("7. Read its ride name, fare, and pickup ETA if shown.\n")
        append("8. Call done EXACTLY like: '${providerLabel(provider)} estimate: ride=<ride>, fare=Rs <amount>, pickup_eta=<eta>'. If pickup ETA is not visible, use pickup_eta=unknown.\n")
    }

    private fun buildBookingGoal(
        provider: String,
        pickup: String,
        destination: String,
        transport: String,
        chosen: RideEstimate,
    ): String = buildString {
        val transportGuidance = transportGuidance(provider, transport)
        val chosenRide = chosen.rideType.ifBlank { defaultRideHint(provider, transport) }
        val useCurrentPickup = pickup.isBlank()
        append("You are in the ${providerLabel(provider)} app. Book the selected ride from \"${
            pickup.ifBlank { "current location" }
        }\" to \"$destination\".\n\n")
        append("RULES:\n")
        if (!useCurrentPickup) {
            append("- Use \"$pickup\" as the pickup/origin. Do NOT keep GPS/current location.\n")
        } else {
            append("- Use the current location as the pickup/origin.\n")
        }
        append("- Placeholder text such as 'Enter pickup location', 'Current location', 'Where to?', or 'Search destination' is only a field label. Never type the label words themselves.\n")
        append("- Never tap the tiny X / clear icon inside the pickup field when you mean to focus or select the field.\n")
        append("- $transportGuidance\n")
        append("- Prefer the ride option named \"$chosenRide\" if it is visible.\n")
        append("- If that exact ride is not visible, choose the cheapest matching ride in the same transport category.\n")
        append("- Do NOT enter any payment PIN or card details.\n")
        append("- Book for right now. Do not schedule or reserve.\n\n")
        append("STEPS:\n")
        append("1. Tap the destination or 'Where to?' field.\n")
        append("2. If a two-field location picker appears, the TOP field is pickup and the BOTTOM field is destination.\n")
        if (!useCurrentPickup) {
            append("3. Tap INSIDE the TOP pickup field itself, not the X / clear icon. Type ONLY \"$pickup\" and choose the best pickup suggestion BEFORE touching destination.\n")
            append("4. Tap INSIDE the BOTTOM destination field. Use the placeholder only to identify the field, then type ONLY \"$destination\" and choose the best suggestion.\n")
        } else {
            append("3. Leave the pickup field on the app's current-location / GPS value. If the app asks to use or confirm the current location, choose that option and do not erase the pickup.\n")
            append("4. Tap INSIDE the destination field. Use the placeholder only to identify the field, then type ONLY \"$destination\" and choose the best suggestion.\n")
        }
        append("5. Wait for the ride options screen.\n")
        append("6. Pick the ride \"$chosenRide\" if visible, otherwise the cheapest matching option in the correct transport category.\n")
        append("7. Tap the main request/confirm button to book the ride.\n")
        append("8. Stop before any payment PIN or card screen.\n")
        append("9. Call done EXACTLY like: '${providerLabel(provider)} booked: ride=<ride>, fare=Rs <amount>, pickup_eta=<eta>'. If pickup ETA is not visible, use pickup_eta=unknown.\n")
    }

    private fun parseEstimate(provider: String, raw: String): RideEstimate {
        val rideType = Regex("""ride=([^,\n]+)""", RegexOption.IGNORE_CASE)
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()

        val pickupEtaText = Regex("""pickup_eta=([^,\n]+)""", RegexOption.IGNORE_CASE)
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "unknown"

        return RideEstimate(
            provider = provider,
            rideType = rideType,
            fareRupees = parseFare(raw),
            pickupEtaText = pickupEtaText,
            raw = raw,
        )
    }

    private fun chooseRide(
        estimates: List<RideEstimate>,
        providerPreference: String?,
        selectionPreference: String,
    ): RideEstimate? {
        val usable = estimates.filter { it.raw.isNotBlank() }
        if (usable.isEmpty()) return null

        if (providerPreference != null) {
            return usable.firstOrNull { it.provider == providerPreference } ?: usable.first()
        }

        return when (selectionPreference) {
            "fastest" -> usable.minByOrNull { parseDurationMinutes(it.pickupEtaText) ?: Int.MAX_VALUE }
                ?: usable.first()
            else -> usable.minByOrNull { it.fareRupees ?: Int.MAX_VALUE } ?: usable.first()
        }
    }

    private fun buildConfirmationPrompt(
        destination: String,
        pickup: String,
        routeEta: RouteEta,
        estimates: List<RideEstimate>,
        chosen: RideEstimate,
        contact: String,
        outboundMessage: String,
    ): String = buildString {
        appendLine("*Ready to book this ride*")
        appendLine()
        appendLine("*Route*")
        appendLine("- Pickup: ${routeEta.pickupText.ifBlank { pickup.ifBlank { "Current location" } }}")
        appendLine("- Destination: ${routeEta.destinationText.ifBlank { destination }}")
        appendLine("- Mode: ${routeEta.modeLabel}")
        appendLine("- ETA: ${routeEta.text}")
        appendLine("- Distance: ${routeEta.distanceText}")
        appendLine()
        appendLine("*Chosen Ride*")
        appendLine("- Provider: ${chosen.providerLabel()}")
        appendLine("- Ride: ${chosen.rideType.ifBlank { "Best matching option" }}")
        appendLine("- Fare: ${chosen.fareLabel()}")
        appendLine("- Pickup ETA: ${chosen.pickupEtaText.ifBlank { "unknown" }}")
        appendLine()
        appendLine("*Compared:*")
        estimates.forEach { appendLine("- ${it.providerLabel()} ${it.rideType} ${it.fareLabel()} ${it.pickupEtaLabel()}") }
        if (contact.isNotBlank()) {
            appendLine()
            appendLine("*WhatsApp:* $contact")
            appendLine(outboundMessage)
        }
        appendLine()
        append("Reply *YES* to book the ride${if (contact.isNotBlank()) " and send the message" else ""}.")
    }

    private fun buildOutboundMessage(
        baseMessage: String,
        destination: String,
        routeEta: RouteEta,
        chosen: RideEstimate,
    ): String {
        val cleanedBase = baseMessage.trim().trimEnd('.', '!', '?')
        val etaPart = when {
            routeEta.text != "unknown" -> "ETA to $destination is about ${routeEta.text}"
            else -> "Heading to $destination now"
        }
        val providerPart = when {
            chosen.rideType.isNotBlank() -> " via ${chosen.providerLabel()} ${chosen.rideType}"
            else -> " via ${chosen.providerLabel()}"
        }
        return "$cleanedBase. $etaPart$providerPart."
    }

    private fun formatRouteEta(routeEta: RouteEta): String {
        return when {
            routeEta.text != "unknown" && routeEta.distanceText != "unknown" ->
                "${routeEta.text}, ${routeEta.distanceText} (${routeEta.modeLabel})"
            routeEta.text != "unknown" -> "${routeEta.text} (${routeEta.modeLabel})"
            else -> "ETA unavailable"
        }
    }

    private fun RideEstimate.providerLabel(): String = providerLabel(provider)

    private fun RideEstimate.fareLabel(): String = fareRupees?.let { "Rs $it" } ?: raw.take(80)

    private fun RideEstimate.pickupEtaLabel(): String = "pickup ETA ${pickupEtaText.ifBlank { "unknown" }}"

    private fun normalizeProvider(raw: String?): String? {
        val text = raw?.trim()?.lowercase().orEmpty()
        return when {
            text.isBlank() -> null
            "uber" in text -> "uber"
            "ola" in text -> "ola"
            "rapido" in text -> "rapido"
            else -> null
        }
    }

    private fun normalizeTransport(raw: String?): String {
        val text = raw?.trim()?.lowercase().orEmpty()
        return when {
            text.contains("auto") || text.contains("rickshaw") -> "auto"
            text.contains("bike") || text.contains("moto") || text.contains("scooter") -> "bike"
            else -> "cab"
        }
    }

    private fun normalizeSelectionPreference(raw: String?): String {
        val text = raw?.trim()?.lowercase().orEmpty()
        return if ("fast" in text) "fastest" else "cheapest"
    }

    private fun mapsModeLabelForTransport(transport: String): String {
        return when (transport) {
            "bike" -> "driving (road route for bike)"
            "auto" -> "driving (road route for auto)"
            else -> "driving (car/cab)"
        }
    }

    private fun transportGuidance(provider: String, transport: String): String {
        return when (transport) {
            "auto" -> "Choose an auto/rickshaw option only. Ignore car, sedan, bike, package, and rental options."
            "bike" -> "Choose a bike or moto option only. Ignore auto, car, sedan, package, and rental options."
            else -> when (provider) {
                "ola" -> "Choose a car/cab option only, such as Mini, Prime Sedan, Sedan, or similar. Ignore Auto, Bike, package, rental, and outstation options."
                "rapido" -> "Choose a Cab or Car option if available; otherwise pick the most suitable non-bike option. Ignore Bike and outstation options."
                else -> "Choose a car/cab option only, such as Uber Go, Go Sedan, Premier, XL, or similar. Ignore Auto, Moto, package, rental, and reserve options."
            }
        }
    }

    private fun defaultRideHint(provider: String, transport: String): String {
        return when (transport) {
            "auto" -> when (provider) { "ola" -> "Auto"; "rapido" -> "Auto"; else -> "Uber Auto" }
            "bike" -> when (provider) { "ola" -> "Bike"; "rapido" -> "Bike"; else -> "Moto" }
            else -> when (provider) { "ola" -> "Mini"; "rapido" -> "Cab"; else -> "Uber Go" }
        }
    }

    private fun providerLabel(provider: String): String = when (provider) {
        "rapido" -> "Rapido"
        else -> provider.replaceFirstChar { it.uppercase() }
    }

    private fun parseFare(text: String): Int? {
        val match = Regex("""(?:\u20B9|Rs\.?|INR)\s*([\d,]+)""", RegexOption.IGNORE_CASE).find(text)
        return match?.groupValues?.getOrNull(1)?.replace(",", "")?.toIntOrNull()
    }

    private fun extractRouteField(text: String, field: String): String {
        return Regex("""$field=([^,\n]+)""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.trim('"')
            .takeUnless { it.equals("unknown", ignoreCase = true) }
            .orEmpty()
    }

    private fun extractEtaText(text: String): String {
        val patterns = listOf(
            Regex("""\d+\s*hr[s]?\s*\d*\s*min[s]?""", RegexOption.IGNORE_CASE),
            Regex("""\d+\s*hour[s]?\s*\d*\s*minute[s]?""", RegexOption.IGNORE_CASE),
            Regex("""\d+\s*min[s]?""", RegexOption.IGNORE_CASE),
        )
        for (pattern in patterns) {
            pattern.find(text)?.let { return it.value.trim() }
        }
        return "unknown"
    }

    private fun extractDistanceText(text: String): String {
        val patterns = listOf(
            Regex("""\d+(?:\.\d+)?\s*km""", RegexOption.IGNORE_CASE),
            Regex("""\d+(?:\.\d+)?\s*m\b""", RegexOption.IGNORE_CASE),
        )
        for (pattern in patterns) {
            pattern.find(text)?.let { return it.value.trim() }
        }
        return "unknown"
    }

    private fun parseDurationMinutes(text: String): Int? {
        val lower = text.lowercase()
        val hoursAndMinutes = Regex(
            """(\d+)\s*(?:hr|hrs|hour|hours)\s*(\d+)?\s*(?:min|mins|minute|minutes)?""",
            RegexOption.IGNORE_CASE,
        ).find(lower)
        if (hoursAndMinutes != null) {
            val hours = hoursAndMinutes.groupValues[1].toIntOrNull() ?: 0
            val minutes = hoursAndMinutes.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            return hours * 60 + minutes
        }

        val minutesOnly = Regex("""(\d+)\s*(?:min|mins|minute|minutes)""", RegexOption.IGNORE_CASE)
            .find(lower)
        return minutesOnly?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    private fun containsNormalized(actual: String, expected: String): Boolean {
        if (expected.isBlank()) return true
        if (actual.isBlank() || actual == "unknown") return false
        val cleanActual = actual.lowercase().replace(Regex("""[^a-z0-9]+"""), " ").trim()
        val cleanExpected = expected.lowercase().replace(Regex("""[^a-z0-9]+"""), " ").trim()
        return cleanActual.contains(cleanExpected) || cleanExpected.contains(cleanActual)
    }
}
