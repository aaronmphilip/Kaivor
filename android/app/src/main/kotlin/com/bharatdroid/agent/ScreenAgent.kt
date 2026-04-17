package com.bharatdroid.agent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.Base64
import com.bharatdroid.agent.skills.SandboxedRunner
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * ScreenAgent — Universal AI-driven screen controller.
 *
 * Philosophy:
 *  - No app-specific hardcoded rules. Works on ANY Android app.
 *  - Derives meaning from element semantics (role, position, size, traits).
 *  - AI sees a rich description of the screen and decides what to do.
 *  - Plan-then-Execute: generates a numbered plan before acting.
 *  - Voice mode blocked at the semantic level, not by hardcoded names.
 */
class ScreenAgent(
    private val apiKey: String,
    private val provider: AIProvider,
    private val model: String = "",
    private val userMemory: UserMemory? = null,
    private val appKnowledge: AppKnowledgeBase? = null,
) {
    // Stop flag — set by AgentOrchestrator when user sends "stop"
    // Checked at every step of the execution loop
    @Volatile var stopRequested = false
        private set

    // Reference to in-flight OkHttp call — cancelled immediately when stop is requested
    // This is the key to making stop responsive: OkHttp.Call.cancel() throws IOException
    // mid-request, unblocking the coroutine without waiting for the full API response (5-15s)
    @Volatile private var activeCall: okhttp3.Call? = null

    fun requestStop() {
        stopRequested = true
        activeCall?.cancel() // immediately abort any in-progress AI API call
    }
    fun clearStop() { stopRequested = false }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    data class ScreenAction(
        val action: String,
        val index: Int = -1,
        val text: String = "",
        val summary: String = "",
    )

    // ─── Public API ───────────────────────────────────────────────────────────

    /** Tap the most relevant element for an instruction. */
    suspend fun tapSmartly(runner: SandboxedRunner, instruction: String): Boolean {
        val elements = runner.getClickableElements()
        if (elements.isEmpty()) return false
        val screenText = try { runner.readScreen().take(600) } catch (_: Exception) { "" }

        val prompt = buildString {
            appendLine("Pick the best element to tap on this Android screen.")
            appendLine("TASK: $instruction")
            appendLine()
            appendLine("ELEMENTS:")
            appendLine(describeElements(elements))
            appendLine()
            appendLine("SCREEN TEXT: ${screenText.take(400)}")
            appendLine()
            appendLine("RULES: Never tap voice/mic/audio elements. Prefer text input fields over icons.")
            append("Reply with one JSON only: {\"action\":\"tap\",\"index\":N} or {\"action\":\"fail\"}")
        }

        val response = callAI(prompt) ?: return false
        val action = parseAction(response)
        if (action.action != "tap") return false
        val idx = findSafeTapIndex(elements, action.index)
        if (idx !in elements.indices) return false
        val el = elements[idx]
        return runner.tapAtPoint(el.centerX.toFloat(), el.centerY.toFloat())
    }

    /** Main execution loop: plan then execute up to maxSteps actions. */
    suspend fun executeGoal(
        runner: SandboxedRunner,
        goal: String,
        maxSteps: Int = 25,
    ): String {
        // NOTE: Do NOT check stopRequested here.
        // AgentOrchestrator.handleMessage calls requestStop() on every new message to kill
        // any PREVIOUS task, then calls clearStop() right before starting the new task.
        // If we check here, the new task would see the stop flag set by its OWN handleMessage
        // call and immediately return "⛔ Stopped." — causing the "Stopped." spam the user sees.

        val result = executeGoalInternal(runner, goal, maxSteps, isRetry = false)

        // Auto-retry once on failure — but ONLY for "stuck" failures (screen didn't change).
        // Do NOT retry "Reached step limit" — that means the task ran 25+ steps with real effects.
        if (result.startsWith("Could not complete") || result.startsWith("Stuck after")) {
            if (stopRequested) { stopRequested = false; return "⛔ Stopped." }
            delay(1000)
            return executeGoalInternal(runner, goal, maxSteps / 2, isRetry = true, previousFailure = result)
        }
        return result
    }

    private suspend fun executeGoalInternal(
        runner: SandboxedRunner,
        goal: String,
        maxSteps: Int,
        isRetry: Boolean = false,
        previousFailure: String = "",
    ): String {
        // ── Phase 1: Get current package context ─────────────────────────────
        // NOTE: Do NOT call resetToAppHome() here — it caused infinite loops.
        // Deep content signals ("like", "subscribe", "comment") appear on HOME feeds
        // of YouTube/Instagram, causing false positives → pressBack → exit app → loop.
        // Instead, let the AI handle navigation within its goal context.
        val currentPkg = runner.getCurrentPackage()

        // ── Phase 2: Dismiss any overlays ────────────────────────────────────
        repeat(2) {
            dismissVoiceOverlay(runner)
            dismissObstructingPopups(runner)
            delay(100)
        }

        // ── Phase 3: Build contextual goal ───────────────────────────────────
        // Inject: user preferences, per-app knowledge, retry context
        val memoryContext = userMemory?.buildPromptContext() ?: ""
        val appKnowledgeContext = appKnowledge?.getPromptContext(currentPkg) ?: ""

        val contextualGoal = buildString {
            append(goal)
            appendLine()
            if (isRetry && previousFailure.isNotBlank()) {
                appendLine()
                appendLine("⚠️ RETRY: Previous attempt failed: \"${previousFailure.take(120)}\"")
                appendLine("Try a DIFFERENT approach this time. The previous strategy did not work.")
            }
            if (appKnowledgeContext.isNotBlank()) {
                appendLine()
                appendLine(appKnowledgeContext)
            }
            if (memoryContext.isNotBlank()) {
                appendLine()
                appendLine(memoryContext) // already formatted as MANDATORY RULES by UserMemory
            }
        }

        // Generate a plan
        val initialScreen = try { runner.readScreen().take(900) } catch (_: Exception) { "" }
        val initialElements = runner.getClickableElements()
        val plan = generatePlan(contextualGoal, initialScreen, describeElements(initialElements))

        val actionLog = mutableListOf<String>()
        var consecutiveBlockedOrMiss = 0
        var consecutiveBackCount = 0 // tracks back presses to prevent back→exit→reopen loops

        // ── OpenClaw-style stuck detection (AppAgent/DroidClaw research) ─────────
        // Rolling window of screen state hashes — if unchanged for 3 steps, inject recovery hint
        val recentScreenHashes = ArrayDeque<Int>(5)
        var lastTappedIdx = -1
        var repeatTapCount = 0
        var driftCount = 0      // how many times we've been pulled back to targetPkg
        var totalBackCount = 0  // total backs used — blocks cycling even with other actions between

        // Track the target package — if we drift away, force back to target.
        // EXCEPTION: if we started on the home screen / launcher, the AI is supposed to
        // navigate AWAY from home (e.g. "open YouTube"). Don't lock to the launcher.
        val targetPkg = if (isLauncherPackage(currentPkg)) "" else currentPkg

        clearStop() // clear any previous stop request before starting

        for (step in 1..maxSteps) {
            // Check stop flag — user sent "stop" while task was running
            if (stopRequested) {
                stopRequested = false
                return "⛔ Stopped. Left the app where it was."
            }

            delay(110)
            dismissVoiceOverlay(runner)
            // Dismiss popups at every step — Zomato/Swiggy etc. throw popups mid-task
            // (notifications, location, rate-us dialogs). Without this, the agent gets stuck.
            dismissObstructingPopups(runner)

            val elements = runner.getClickableElements()
            val screenText = try { runner.readScreen().take(1200) } catch (_: Exception) { "" }

            if (step == 1 && screenText.isBlank() && elements.isEmpty()) {
                return "Screen is empty — make sure the target app is open."
            }

            // Hash current screen state — text + element count + positions
            val screenHash = (screenText.take(400) + elements.size + elements.sumOf { it.centerY }).hashCode()
            recentScreenHashes.addLast(screenHash)
            if (recentScreenHashes.size > 4) recentScreenHashes.removeFirst()

            // Build stuck recovery hint (injected into AI prompt when agent is looping)
            val isScreenFrozen = recentScreenHashes.size >= 3 &&
                recentScreenHashes.takeLast(3).all { it == screenHash }
            val isRepeatTapping = repeatTapCount >= 3 && lastTappedIdx >= 0
            val stuckHint = buildString {
                if (isScreenFrozen) {
                    appendLine("🚨 STUCK: Screen has NOT changed for 3+ steps. Current approach is failing.")
                    appendLine("TRY SOMETHING DIFFERENT: scroll down to find new elements, tap a different button, or use dismiss.")
                    appendLine("DO NOT press back — it will exit the app.")
                }
                if (isRepeatTapping) {
                    appendLine("🚨 REPEAT TAP: Element #$lastTappedIdx has been tapped $repeatTapCount times with no result. STOP tapping it.")
                    appendLine("Choose a DIFFERENT element or scroll to reveal new elements.")
                }
                if (consecutiveBackCount >= 2) {
                    appendLine("🚨 BACK LOOP: You have pressed back $consecutiveBackCount times in a row.")
                    appendLine("STOP pressing back. Use scroll_down, tap a visible element, or type in a field instead.")
                }
                if (totalBackCount >= 4) {
                    appendLine("🚨 CYCLING: You have pressed back $totalBackCount times total in this task.")
                    appendLine("You are search→back→search→back looping. STOP. Look at results already on screen and TAP one.")
                    appendLine("If you see a list of results, SCROLL to explore them. Do NOT go back to the home page again.")
                }
                if (driftCount >= 2) {
                    appendLine("🚨 APP DRIFT: You have left the target app $driftCount times. You keep navigating away.")
                    appendLine("STOP tapping links, ads, or banners that open other apps. Stay inside this app.")
                    appendLine("Scroll within the current screen instead of tapping things that might navigate away.")
                }
            }.trim()

            // Capture screenshot for vision-based decision making (Set-of-Mark)
            val screenshot: Bitmap? = try {
                runner.captureScreenshot()
            } catch (_: Exception) { null }

            val action = decideNextAction(
                goal = contextualGoal,
                plan = plan,
                elements = elements,
                screenText = screenText,
                history = actionLog,
                screenshot = screenshot,
                stuckHint = stuckHint,
            )

            // Check stop AFTER the AI call — AI calls take 5-15s so this fires much faster
            // than waiting for the next iteration's top-of-loop check
            if (stopRequested) {
                stopRequested = false
                return "⛔ Stopped."
            }

            // Track tap repetition — if AI keeps tapping same element, flag it next step
            if (action.action == "tap") {
                if (action.index == lastTappedIdx) repeatTapCount++
                else { lastTappedIdx = action.index; repeatTapCount = 1 }
            } else if (action.action !in listOf("wait", "dismiss")) {
                repeatTapCount = 0 // reset on any real action
            }

            val result = executeAction(runner, action, elements, consecutiveBackCount, totalBackCount)

            // Track consecutive back presses (for blocking 2-in-a-row)
            // and total back presses (for blocking search→back→search→back cycling)
            if (action.action == "back") {
                consecutiveBackCount++
                // Only count backs that actually went somewhere — not BLOCKED and not EXIT CAUGHT
                // (EXIT CAUGHT means back was reversed by re-opening the app, so it didn't "work")
                if (!result.contains("BLOCKED") && !result.contains("EXIT CAUGHT")) totalBackCount++
            } else {
                consecutiveBackCount = 0
            }

            // ── App drift guard ──────────────────────────────────────────────
            // After EVERY action, check if we've drifted away from the target app.
            // targetPkg is blank when we started on the home screen (GeneralSkill cross-app
            // tasks) — in that case we skip this guard so the AI can freely open apps.
            if (targetPkg.isNotBlank()) {
                val nowPkg = try { runner.getCurrentPackage() } catch (_: Exception) { "" }
                val isOkOverlay = nowPkg.contains("android.inputmethod")    // keyboard
                    || nowPkg.contains("permissioncontroller")               // permission dialog
                    || nowPkg.contains("systemui")                           // notification shade
                    || nowPkg.contains("packageinstaller")                   // install dialog
                if (nowPkg.isNotBlank() && nowPkg != targetPkg && !isOkOverlay) {
                    driftCount++
                    val driftMsg = if (driftCount >= 2) {
                        "⛔ DRIFT #$driftCount: You keep leaving $targetPkg (went to $nowPkg). " +
                        "CRITICAL: Stay inside $targetPkg. Do NOT tap links that open other apps. " +
                        "Do NOT press home. Scroll and search WITHIN $targetPkg only."
                    } else {
                        "DRIFTED to $nowPkg → re-opened $targetPkg. Stay inside $targetPkg."
                    }
                    try {
                        runner.openApp(targetPkg)
                        runner.waitForApp(targetPkg, timeoutMs = 4000)
                        delay(400)
                        actionLog += driftMsg
                    } catch (_: Exception) {
                        // Runner may not have OPEN_APP permission — log but don't crash
                        actionLog += "DRIFTED to $nowPkg (could not re-open $targetPkg)"
                    }
                }
            }

            when (result) {
                "DONE" -> {
                    val outcome = action.summary.ifBlank { "Done." }
                    // Save successful pattern to app knowledge base (AppAgent technique)
                    if (currentPkg.isNotBlank() && !isRetry) {
                        appKnowledge?.saveSuccess(currentPkg, goal, outcome, actionLog)
                    }
                    return outcome
                }
                "FAIL" -> return "Could not complete: ${action.summary.ifBlank { "Stuck with no safe next step." }}"
                else -> {
                    if (result.contains("BLOCKED") || result.contains("MISS") || result.contains("SKIP")) {
                        consecutiveBlockedOrMiss++
                        if (consecutiveBlockedOrMiss >= 4) {
                            return "Stuck after $step steps: $result\n${screenText.take(200)}"
                        }
                    } else {
                        consecutiveBlockedOrMiss = 0
                    }
                    actionLog += result
                }
            }
        }

        // Step limit hit — build a HUMAN-FRIENDLY summary instead of dumping raw screen text.
        // The raw screen often contains noise like repeated "Vehicle Vehicle Vehicle..." which
        // leaks internal element labels to the user. Extract only the meaningful signals:
        //   - Prices/fares (₹420, Rs 1,200)
        //   - ETAs (3 min away, drop-off 1:48pm)
        //   - Obvious success markers (Confirmed, Booked, Added to cart, Order placed)
        //   - Next-step prompt (Choose Uber Go, Confirm details)
        val finalScreen = try { runner.readScreen() } catch (_: Exception) { "" }
        return buildStepLimitSummary(finalScreen, maxSteps, goal)
    }

    /**
     * Produces a clean user-facing summary when the agent exhausts its step budget.
     * Avoids dumping raw repeated element labels by scanning the screen for semantic signals.
     */
    private fun buildStepLimitSummary(screen: String, maxSteps: Int, goal: String): String {
        if (screen.isBlank()) return "Reached step limit ($maxSteps) — no screen content to report."

        // De-duplicate lines and drop obvious UI noise.
        val rawLines = screen.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length in 2..140 }
            .toList()
        val seen = mutableSetOf<String>()
        val uniqueLines = rawLines.filter { seen.add(it.lowercase()) }

        // Extract signals.
        val priceRegex = Regex("""(?:₹|Rs\.?|INR)\s*[\d,]+(?:\.\d+)?""", RegexOption.IGNORE_CASE)
        val etaRegex = Regex("""\b\d+\s*(?:min|mins|minute|minutes|hr|hrs|hour|hours)\b""", RegexOption.IGNORE_CASE)
        val timeRegex = Regex("""\b\d{1,2}:\d{2}\s*(?:am|pm)?\b""", RegexOption.IGNORE_CASE)

        val prices = priceRegex.findAll(screen).map { it.value }.distinct().take(3).toList()
        val etas = etaRegex.findAll(screen).map { it.value }.distinct().take(3).toList()
        val times = timeRegex.findAll(screen).map { it.value }.distinct().take(3).toList()

        val successMarkers = listOf(
            "confirmed", "booked", "order placed", "added to cart", "payment successful",
            "ride confirmed", "trip booked", "scheduled", "saved",
        )
        val hasSuccess = successMarkers.any { screen.contains(it, ignoreCase = true) }

        val actionPrompts = uniqueLines.filter { line ->
            val l = line.lowercase()
            l.startsWith("confirm") || l.startsWith("choose ") || l.startsWith("book ") ||
                l.startsWith("schedule") || l.startsWith("pay ") || l == "continue" || l == "next"
        }.take(3)

        return buildString {
            if (hasSuccess) {
                appendLine("✅ Task mostly completed (hit step limit on the final confirmation).")
            } else {
                appendLine("⏱️ Reached step limit ($maxSteps) while working on: \"${goal.take(80)}\"")
            }
            if (prices.isNotEmpty()) appendLine("💰 Prices seen: ${prices.joinToString(", ")}")
            if (etas.isNotEmpty())   appendLine("⏰ ETA: ${etas.joinToString(", ")}")
            if (times.isNotEmpty())  appendLine("🕐 Times: ${times.joinToString(", ")}")
            if (actionPrompts.isNotEmpty()) {
                appendLine("👉 Next step visible on screen: ${actionPrompts.joinToString(" / ")}")
                appendLine("Tap the button manually to finish, or tell me to continue.")
            }
            if (prices.isEmpty() && etas.isEmpty() && actionPrompts.isEmpty()) {
                // Fall back to a tiny preview — first 3 unique lines, capped.
                val preview = uniqueLines.take(3).joinToString(" · ").take(180)
                if (preview.isNotBlank()) appendLine("Screen preview: $preview")
            }
        }.trim()
    }

    /** Quick single action decision (used by tapSmartly callers). */
    suspend fun decideSingleAction(runner: SandboxedRunner, instruction: String): ScreenAction {
        val elements = runner.getClickableElements()
        val screenText = try { runner.readScreen().take(1000) } catch (_: Exception) { "" }
        return decideNextAction(instruction, "", elements, screenText, emptyList())
    }

    // ─── Planning ─────────────────────────────────────────────────────────────

    private suspend fun generatePlan(goal: String, screenText: String, elements: String): String {
        val prompt = buildString {
            appendLine("You are a mobile UI planning expert. Create a step-by-step action plan for a phone agent.")
            appendLine()
            appendLine("GOAL: $goal")
            appendLine()
            appendLine("CURRENT SCREEN:")
            appendLine(screenText.take(600))
            appendLine()
            appendLine("CLICKABLE ELEMENTS:")
            appendLine(elements.take(700))
            appendLine()
            appendLine("Write a numbered plan (4-10 steps) that:")
            appendLine("1. Does EXACTLY what the goal says — no extra steps, no assumptions")
            appendLine("2. Is specific about what to look for (element text, type, position)")
            appendLine("3. States the action clearly (tap, type, scroll, enter, wait)")
            appendLine("4. Includes how to verify each step succeeded")
            appendLine("5. For search tasks: search-field → type → submit → scroll → pick result")
            appendLine("6. NEVER includes voice/mic/audio buttons")
            appendLine("7. Stops exactly when the goal is achieved — does not add bonus actions")
            appendLine()
            appendLine("Reply with JSON only: {\"plan\":\"Step 1: ... Step 2: ... Step 3: ...\"}")
        }

        return try {
            val response = callAI(prompt) ?: return goal
            val json = extractJsonObject(response) ?: return goal
            val obj = JsonParser.parseString(json).asJsonObject
            obj.get("plan")?.asString ?: goal
        } catch (_: Exception) {
            goal
        }
    }

    // ─── Voice / Popup Dismissal ──────────────────────────────────────────────

    private suspend fun dismissVoiceOverlay(runner: SandboxedRunner) {
        val screen = try { runner.readScreen().lowercase() } catch (_: Exception) { return }

        // ⚠️  STRICT list — ONLY phrases that appear EXCLUSIVELY inside the active
        //     voice-capture modal. Adding anything broader causes pressBack() to fire
        //     on normal screens and sends the agent to the app home → loop.
        //
        // REMOVED (were causing YouTube home-page loop):
        //   "search by voice"  → is a BUTTON LABEL in YouTube's search UI, NOT modal text
        //   "listening"        → appears in music apps ("Now Listening"), video content
        //   "voice search"     → appears as a settings label, help text, button label
        //   "try saying"       → appears in suggestion UI outside the voice modal
        //   "voice input"      → Android settings label, not just modal
        //   "say something"    → too broad: appears in chat apps, assistant prompts
        //   "listening to you" → can appear as marketing copy / regular text
        //
        // "speak now" is the only phrase that appears ONLY in the Google voice modal.
        val strictModalOnly = listOf(
            "speak now",   // Google voice modal heading — never appears elsewhere on screen
        )
        if (strictModalOnly.any { screen.contains(it) }) {
            runner.pressBack()
            delay(280)
        }
    }

    private suspend fun dismissObstructingPopups(runner: SandboxedRunner) {
        try {
            val screen = runner.readScreen().lowercase()

            // IMPORTANT: Only match SPECIFIC multi-word popup phrases, NOT single common words.
            //
            // Banned from this list: "allow", "skip", "got it", "explore", "get started",
            // "dismiss", "block", "deny" — these single words appear in NORMAL app content
            // (YouTube "Skip" button, Amazon "Allow" in permissions that aren't dialogs, etc.)
            // If we matched them and dismissPopups() returned 0 (no button found), the old
            // code pressed back — which EXITS THE APP and causes the home screen loop.
            //
            // Rule: Only add a signal if it's a multi-word phrase that ONLY appears in popups.
            val popupSignals = listOf(
                // Specific permission dialog phrases
                "not now", "maybe later", "no thanks", "no, thanks",
                "remind me later", "update available",
                // Notifications / location — use full phrases, not single words
                "enable notifications", "turn on notifications", "allow notifications",
                "allow location access", "enable location access",
                "use my location", "location permission", "share location",
                // App store / update (specific phrases)
                "update now", "update app", "rate us", "rate app", "rate on play store",
                "rate now", "review app", "leave a review",
                // Food delivery (long specific phrases only)
                "claim offer", "view offer", "grab offer",
                // Payment apps (specific phrases)
                "set upi pin", "complete kyc", "verify your number",
            )

            if (popupSignals.any { screen.contains(it) }) {
                // Try to dismiss via actual popup buttons — never press back as a fallback.
                // pressBack() here would exit the current app and cause home-screen loops.
                // If dismissPopups() finds nothing, there's probably no real popup — leave it.
                runner.dismissPopups(2)
                delay(200)
            }
        } catch (_: Exception) { /* ignore */ }
    }

    // ─── Action Execution ─────────────────────────────────────────────────────

    private suspend fun executeAction(
        runner: SandboxedRunner,
        action: ScreenAction,
        elements: List<ScreenElement>,
        consecutiveBackCount: Int = 0,
        totalBackCount: Int = 0,
    ): String {
        return when (action.action) {

            "tap" -> {
                val idx = findSafeTapIndex(elements, action.index)
                if (idx !in elements.indices) {
                    return "tap[${action.index}] SKIP — index out of range"
                }
                val el = elements[idx]
                val role = elementRole(el)

                if (role == "voice") {
                    return "tap[$idx] BLOCKED — voice/mic element, skipping"
                }

                // Block tapping the bottom-nav Home tab — it resets to the app's home feed,
                // losing all search results and creating a search→home→search→home loop.
                // The AI should scroll and navigate within the current screen instead.
                if (role == "nav-home") {
                    return "tap[$idx] BLOCKED — bottom Home tab would lose search results. Scroll or tap a result instead."
                }

                val ok = runner.tapAtPoint(el.centerX.toFloat(), el.centerY.toFloat())
                // Adaptive wait: polls until screen changes — handles slow Indian 4G gracefully
                // Faster on fast networks, never fires early on slow ones (replaces fixed delay)
                runner.waitForScreenChange(timeoutMs = 3000)
                val label = bestLabel(el).take(30).ifBlank { role }
                val idxLabel = if (idx == action.index) "$idx" else "${action.index}→$idx"
                "tap[$idxLabel] '$label' → ${if (ok) "OK" else "MISS"}"
            }

            "type" -> {
                // Trust the AI's chosen index if it points to a valid editable field.
                // Only fall back to best-scoring field if the index is invalid.
                val typeIdx = if (action.index in elements.indices && elements[action.index].isEditable)
                    action.index
                else
                    findBestInputIndex(elements, action.index)

                // Determine the field's role to decide REPLACE vs APPEND behaviour:
                //
                //  • SEARCH fields (search bars, query boxes) → REPLACE mode:
                //    Clear any stale query first, then type the new one fresh.
                //    Reason: each new search should overwrite the previous query.
                //
                //  • ALL OTHER fields (notes, messages, forms, document editors) → APPEND mode:
                //    Read existing content first, combine, then ACTION_SET_TEXT.
                //    Reason: ACTION_SET_TEXT alone WIPES the entire field. Without append,
                //    typing paragraph 2 erases paragraph 1 — the exact bug the user reported.
                val fieldRole = if (typeIdx in elements.indices) elementRole(elements[typeIdx]) else "text-input"
                val isSearchField = fieldRole == "search-input"

                if (typeIdx in elements.indices) {
                    val el = elements[typeIdx]

                    // Hint-based targeting: uses field's own hint to find the exact node.
                    // For APPEND mode, pass appendToExisting=true so typeInFieldWithHint
                    // reads existing content and combines before ACTION_SET_TEXT.
                    val hint = el.hint.ifBlank { el.contentDescription }
                    if (hint.isNotBlank()) {
                        val hintOk = runner.typeInFieldWithHint(hint, action.text, appendToExisting = !isSearchField)
                        if (hintOk) {
                            delay(150)
                            return "type[$typeIdx] '${action.text.take(30)}' → OK (hint${if (!isSearchField) "+append" else ""})"
                        }
                    }
                    // Tap the specific field the AI chose — establishes focus before typing
                    runner.tapAtPoint(el.centerX.toFloat(), el.centerY.toFloat())
                    delay(300) // give focus time to settle on Indian phones
                }

                val ok = if (isSearchField) {
                    // REPLACE mode: clear stale query then type fresh
                    // Safe clear via ACTION_SET_TEXT("") — no selection chaos
                    runner.clearField()
                    runner.typeReliably(action.text)
                } else {
                    // APPEND mode: read existing content, combine, then ACTION_SET_TEXT
                    // This preserves existing paragraphs/lines in notes, messages, forms
                    runner.typeAppending(action.text)
                }
                delay(150)
                val idxLabel = if (typeIdx in elements.indices) "$typeIdx" else "auto"
                val modeTag = if (isSearchField) "replace" else "append"
                "type[$idxLabel/$modeTag] '${action.text.take(30)}' → ${if (ok) "OK" else "MISS"}"
            }

            // Move focus to the next editable field (Tab equivalent)
            // Used in multi-field forms: after typing email, call next_field to move to password
            "next_field" -> {
                // Find the next editable field after the current index
                val nextIdx = elements.indices
                    .drop((action.index + 1).coerceAtLeast(0))
                    .firstOrNull { elements[it].isEditable }
                if (nextIdx != null) {
                    val next = elements[nextIdx]
                    runner.tapAtPoint(next.centerX.toFloat(), next.centerY.toFloat())
                    delay(250)
                    "next_field → tapped field[$nextIdx] '${next.hint.ifBlank { next.text }.take(20)}'"
                } else {
                    // No next field — press enter to submit or move to next line
                    val ok = runner.pressEnter()
                    delay(200)
                    "next_field → enter ${if (ok) "OK" else "MISS"}"
                }
            }

            "scroll_down" -> {
                val ok = runner.scrollDown() || runner.swipeUp()
                delay(250)
                if (ok) "scroll_down" else "scroll_down MISS"
            }

            "scroll_up" -> {
                val ok = runner.scrollUp() || runner.swipeDown()
                delay(250)
                if (ok) "scroll_up" else "scroll_up MISS"
            }

            "swipe_left" -> {
                runner.swipeLeft()
                delay(250)
                "swipe_left"
            }

            "swipe_right" -> {
                runner.swipeRight()
                delay(250)
                "swipe_right"
            }

            "long_press" -> {
                val ok = if (action.index in elements.indices) runner.longPress(action.index) else false
                delay(350)
                "long_press[${action.index}] → ${if (ok) "OK" else "MISS"}"
            }

            "toggle" -> {
                val ok = if (action.index in elements.indices) runner.toggle(action.index) else false
                delay(200)
                "toggle[${action.index}] → ${if (ok) "OK" else "MISS"}"
            }

            "back" -> {
                // Block consecutive backs — 2 in a row always exits the current screen/app
                if (consecutiveBackCount >= 2) {
                    return "back BLOCKED — pressed back ${consecutiveBackCount} times in a row. Use scroll_down, tap a different element, or type instead."
                }
                // Block total backs — prevents search→back→search→back cycling loops
                // even when other actions are interspersed between backs
                if (totalBackCount >= 6) {
                    return "back BLOCKED — already used back ${totalBackCount} times in this task. STOP going back. Scroll down through results or tap a result to open it."
                }

                // Check package BEFORE pressing back — if back exits the app, re-open it
                val pkgBefore = runner.getCurrentPackage()
                runner.pressBack()
                delay(300)
                val pkgAfter = runner.getCurrentPackage()
                // If we left the target app, re-open it immediately
                if (pkgBefore.isNotBlank() && pkgAfter != pkgBefore) {
                    try {
                        runner.openApp(pkgBefore)
                        runner.waitForApp(pkgBefore, timeoutMs = 4000)
                        delay(400)
                    } catch (_: Exception) { /* no OPEN_APP permission */ }
                    "back → EXIT CAUGHT, re-opened $pkgBefore"
                } else {
                    "back"
                }
            }

            "home" -> {
                // Block going to device home screen during skill execution —
                // this was causing food/shopping loops where agent went home,
                // then re-opened the app, then went home again endlessly.
                // The agent should navigate WITHIN apps, not to the device home.
                "home BLOCKED — use back or navigate within app instead"
            }

            "enter" -> {
                val ok = runner.pressEnter()
                delay(300)
                "enter → ${if (ok) "OK" else "MISS"}"
            }

            "dismiss" -> {
                runner.dismissPopups(1)
                delay(200)
                "dismiss"
            }

            "wait" -> {
                delay(700)
                "wait"
            }

            "done" -> "DONE"
            "fail" -> "FAIL"

            else -> "unknown:${action.action}"
        }
    }

    // ─── AI Decision ──────────────────────────────────────────────────────────

    private suspend fun decideNextAction(
        goal: String,
        plan: String,
        elements: List<ScreenElement>,
        screenText: String,
        history: List<String>,
        screenshot: Bitmap? = null,
        stuckHint: String = "",
    ): ScreenAction {
        val historyStr = if (history.isEmpty()) "none" else history.takeLast(10).joinToString(" → ")

        // Annotate screenshot with numbered boxes if available (Set-of-Mark technique)
        val annotated = if (screenshot != null) annotateScreenshot(screenshot, elements) else null
        val usingVision = annotated != null && provider == AIProvider.GEMINI

        val prompt = buildString {
            appendLine("You control an Android phone. Decide the SINGLE best next action to make progress.")
            appendLine("Do EXACTLY what the GOAL says. Nothing more. Nothing less. Stop the moment it is done.")
            appendLine()
            appendLine("GOAL: $goal")
            appendLine()
            if (plan.isNotBlank() && plan != goal) {
                appendLine("PLAN:")
                appendLine(plan)
                appendLine()
            }
            appendLine("HISTORY (what happened so far): $historyStr")
            appendLine()

            // Inject stuck recovery hints when the agent is looping (from AppAgent/DroidClaw research)
            if (stuckHint.isNotBlank()) {
                appendLine("══ RECOVERY ALERT ══")
                appendLine(stuckHint)
                appendLine()
            }

            if (usingVision) {
                appendLine("SCREENSHOT: You can see the annotated screenshot of the current screen.")
                appendLine("Each UI element has a NUMBERED COLORED BOX drawn on it.")
                appendLine("- GREEN boxes = text input / search fields (safe to type in)")
                appendLine("- YELLOW boxes = search icons (tap to open search)")
                appendLine("- ORANGE boxes = action buttons (send, buy, confirm)")
                appendLine("- BLUE boxes = message input fields")
                appendLine("- PURPLE boxes = list items / content rows")
                appendLine("- TEAL boxes = toggle switches")
                appendLine("- RED/unlabeled = voice/mic buttons — DO NOT tap these")
                appendLine("Use the NUMBER in the box corner as the 'index' in your action.")
                appendLine()
                appendLine("LOOK AT THE SCREENSHOT CAREFULLY. Use visual info to pick the right element.")
            } else {
                appendLine("SCREEN TEXT:")
                appendLine(screenText.take(900))
                appendLine()
                appendLine("UI ELEMENTS (index, label, role, position, size, traits):")
                appendLine(describeElements(elements).take(1000))
            }
            appendLine()
            appendLine("═══ CRITICAL RULES ═══")
            appendLine("1. STAY IN THE CURRENT SCREEN. NEVER press back after you have search results or content on screen.")
            appendLine("   Back = you lose the results and go back to the app home. Then you'll have to search again = LOOP.")
            appendLine("   If you see results/items on screen → SCROLL through them or TAP one. NEVER press back.")
            appendLine("   Only press back if you are on a completely wrong screen with NO useful content at all.")
            appendLine()
            appendLine("2. CORRECT FIELD TARGETING:")
            appendLine("   - SEARCH BAR = top of screen, role=search-input, used for SEARCHING products/contacts/videos")
            appendLine("   - MESSAGE INPUT = bottom of screen, role=message-input, used for TYPING messages")
            appendLine("   - If the goal is 'send a message', find the MESSAGE input (bottom), NOT the search bar (top)")
            appendLine("   - If the goal is 'search for X', find the SEARCH input (top)")
            appendLine("   - NEVER type a message body into a search bar. NEVER type a search query into a message field.")
            appendLine()
            appendLine("3. VOICE/MIC/CAMERA ICONS — DO NOT TAP:")
            appendLine("   - Mic icon, voice search, camera icon, lens icon, barcode scanner = NEVER tap these")
            appendLine("   - They are small icons NEXT TO the search bar. Tap the WIDE text field instead.")
            appendLine("   - If 'Speak now' or 'Listening' appears → press back immediately.")
            appendLine()
            appendLine("4. SEARCH FLOW (Amazon, Flipkart, YouTube, Zomato, Swiggy, etc.):")
            appendLine("   a) Find and TAP the search bar (wide editable field at top, NOT the mic/camera icon next to it)")
            appendLine("      Amazon specifically: the search bar is wide in the CENTER. On its RIGHT are small icons:")
            appendLine("      📷 camera/photo search and 🎤 mic/voice search — NEVER tap these small icons.")
            appendLine("      Tap only the WIDE TEXT AREA in the middle of the bar.")
            appendLine("   b) TYPE the search query (text, never voice)")
            appendLine("   c) Press ENTER to submit")
            appendLine("   d) WAIT for results to load — results appear AS A LIST below the search bar")
            appendLine("   e) SCROLL DOWN through the product list to see options")
            appendLine("   f) TAP on a product card to see its details")
            appendLine()
            appendLine("5. SHOPPING/ORDERING FLOW:")
            appendLine("   a) Search → find item → tap to view details")
            appendLine("   b) On detail page: scroll down to read reviews, ratings, features, price")
            appendLine("   c) Look for filters (sort by price, rating, relevance) and apply if needed")
            appendLine("   d) SCROLL the results list to compare — do NOT press back to the search page")
            appendLine("   e) Once satisfied, tap 'Add to Cart' or 'Buy Now' or 'Order'")
            appendLine("   f) Fill delivery/payment details if asked")
            appendLine("   g) STOP before final payment — report what you found to the user")
            appendLine()
            appendLine("6. MULTI-FIELD FORMS AND MULTI-LINE TEXT:")
            appendLine("   - For SEPARATE fields (title + body, email + password): each field has its own index.")
            appendLine("     Type into title field → next_field → type into body field (different indices).")
            appendLine("   - For MULTI-LINE TEXT inside ONE field (notes, long messages): use \\n in your text.")
            appendLine("     Example: type {\"text\": \"Line 1\\nLine 2\\nLine 3\"} in ONE type action — do NOT type each line separately.")
            appendLine("     The system automatically APPENDS your text to what is already in the field.")
            appendLine("     You do NOT need to press enter between paragraphs — just include \\n in your text value.")
            appendLine("   - NEVER type the same content twice into the same field.")
            appendLine("   - For login forms: READ THE PLACEHOLDERS/HINTS on each field to know what goes where.")
            appendLine("     role=email-input or hint='Email' → type ONLY the email address.")
            appendLine("     role=password-input or hint='Password' → type ONLY the password (NEVER type email here).")
            appendLine("     role=phone-input or hint='Mobile' → type ONLY the phone number.")
            appendLine("     role=otp-input or hint='OTP/Code' → type ONLY the verification code.")
            appendLine("     role=username-input → type ONLY the username.")
            appendLine("   - NEVER concatenate email+password into one field. Each field gets its OWN value.")
            appendLine("   - If two editable fields are visible, the TOP one is usually email/username, the BOTTOM one is password.")
            appendLine("     Type into field 1 → next_field → type into field 2. Use next_field, do NOT retype the first value.")
            appendLine()
            appendLine("7. AFTER TYPING: if it's a search field → press enter. If it's a form → use next_field.")
            appendLine("7a. INSTRUCTION vs VALUE — VERY IMPORTANT:")
            appendLine("    If the user's goal contains a FORMATTING DIRECTIVE, APPLY the transformation to the")
            appendLine("    value before typing. Do NOT type the directive itself as literal text.")
            appendLine("    Examples:")
            appendLine("      Goal: 'title as hello world with first letter of each word capital'")
            appendLine("        → type {\"text\": \"Hello World\"}  NOT  \"hello world with first letter of each word capital\"")
            appendLine("      Goal: 'name as john in all caps'")
            appendLine("        → type {\"text\": \"JOHN\"}  NOT  \"john in all caps\"")
            appendLine("      Goal: 'note: remind me tomorrow (make it polite)'")
            appendLine("        → type {\"text\": \"Please remind me tomorrow\"}")
            appendLine("    Common directives to APPLY: 'capital first letter(s)', 'title case', 'uppercase',")
            appendLine("    'lowercase', 'sentence case', 'all caps', 'polite', 'formal', 'shorten', 'add emoji'.")
            appendLine("    Rule of thumb: the part AFTER 'as' / 'title:' / 'name:' is the core value; the rest is an")
            appendLine("    instruction to you about how to format that value.")
            appendLine("8. BOTTOM NAV BAR (Home/Shorts/Library/Subscriptions — role=nav-home at very bottom): NEVER tap.")
            appendLine("   Tapping it resets to the app home feed and loses ALL search results = instant loop.")
            appendLine("   Navigate within the current screen using scroll_down, scroll_up, or tap visible results.")
            appendLine("9. If same element failed twice → try scroll_down, or tap a DIFFERENT element.")
            appendLine("10. done only when you CONFIRM success is visible on screen.")
            appendLine("11. fail only if truly stuck with absolutely no path forward.")
            appendLine()
            appendLine("ACTIONS:")
            appendLine("""tap:{"action":"tap","index":N}  type:{"action":"type","index":N,"text":"X"}  enter:{"action":"enter"}""")
            appendLine("""next_field:{"action":"next_field","index":N}  scroll_down:{"action":"scroll_down"}  scroll_up:{"action":"scroll_up"}  back:{"action":"back"}""")
            appendLine("""dismiss:{"action":"dismiss"}  wait:{"action":"wait"}  done:{"action":"done","summary":"X"}  fail:{"action":"fail","summary":"X"}""")
            appendLine()
            // ReAct format — forces model to observe before deciding (from AppAgent research)
            // The "observation" field makes the model describe what it sees, which catches stuck loops naturally
            appendLine("Reply with ONE JSON object in this EXACT format:")
            append("""{"observation":"what you see RIGHT NOW in 1 sentence","thought":"what you will do and why in 1 sentence","action":"tap","index":N}""")
        }

        val response = callAIWithVision(prompt, annotated) ?: callAI(prompt)
            ?: return ScreenAction("fail", summary = "AI no response")
        return parseAction(response)
    }

    // ─── Element Description (Universal, Semantic) ────────────────────────────

    /**
     * Produces a rich, semantic description of UI elements for the AI.
     * No app-specific filtering — the AI sees everything and decides.
     * Voice elements are labeled as role=voice so AI knows to avoid them.
     */
    private fun describeElements(elements: List<ScreenElement>): String {
        if (elements.isEmpty()) return "(no elements)"

        return elements.mapIndexed { i, el ->
            val role = elementRole(el)
            val label = bestLabel(el).take(55).ifBlank { "(unlabeled)" }
            val traits = buildList {
                if (el.isEditable) add("editable")
                if (el.isCheckable) add("checkable")
                if (el.isSelected) add("selected")
                if (el.isScrollable) add("scrollable")
            }.joinToString("+").ifBlank { "clickable" }
            val pos = posTag(el)
            val sz = sizeTag(el)
            val extras = buildList {
                if (el.hint.isNotBlank() && el.hint != el.text) add("hint='${el.hint.take(24)}'")
                if (el.viewId.isNotBlank()) add("id='${el.viewId.substringAfterLast('/').take(22)}'")
            }.joinToString(" ")

            "[$i] role=$role label=\"$label\" pos=$pos size=$sz traits=$traits${if (extras.isNotBlank()) " $extras" else ""}"
        }.joinToString("\n")
    }

    /**
     * Universal semantic role — derived purely from element properties.
     * The AI uses this to understand what each element does without app knowledge.
     */
    private fun elementRole(el: ScreenElement): String {
        val combined = listOf(
            el.text, el.hint, el.contentDescription,
            el.viewId.substringAfterLast('/'), el.className,
        ).joinToString(" ").lowercase()

        // Voice/mic/camera/lens — always marked, AI will avoid these
        // Catches: mic icons, voice search, Amazon camera search, Flipkart lens,
        // barcode scanner, Google Lens — all the non-text-input search triggers
        //
        // Amazon India specific: camera search button has contentDescription "Camera" (caught by
        // "camera") OR "Search by camera"/"Search by photo" (caught by "by camera"/"by photo").
        // Mic button has "Search by voice" (caught by "by voice") or "Microphone" (caught by "microphone").
        //
        // ⚠️  SHORT-TERM MATCHING RULES:
        //   - "mic", "voice", "speak", "snap", "scan" are matched as WHOLE WORDS (word-boundary)
        //     because substring-contains("mic") would also match "comic", "atomic", "ceramic"
        //     and substring-contains("voice") would match "invoice" (payment apps!),
        //     and substring-contains("speak") would match "speaker" (Bluetooth speaker controls).
        //   - Longer unambiguous terms (microphone, camera, barcode, qr_code, etc.) still use
        //     substring matching — they won't appear inside other common words.
        val hasVoiceWord = combined.containsWord("mic", "voice", "speak", "snap")
        val hasVoicePhrase = combined.containsAny(
            "microphone", "speak now",
            "audio_search", "voice_search",
            "camera", "lens", "barcode", "qr_code", "visual_search",
            "image_search", "photo_search", "scanner",
            "by camera", "by photo", "by voice", "search camera", "camera search",
            "search photo", "photo search", "take photo",
        )
        if ((hasVoiceWord || hasVoicePhrase) && !el.isEditable) return "voice"

        return when {
            // Editable fields first (most specific)
            // Password field — detect via hint/id containing "password", "pwd", "pass",
            // or via className=EditText + contentDescription containing password hints.
            // Password fields usually expose the hint "Password" or id "password"/"pwd_edit".
            el.isEditable && combined.containsAny("password", "passwd", "pwd", "pass_word") -> "password-input"
            // OTP / verification code field — hint "OTP", "Enter code", "Verification code"
            el.isEditable && combined.containsAny("otp", "verification code", "verify code", "enter code", "one-time") -> "otp-input"
            // Phone / mobile number field — hint "Phone", "Mobile", "Mobile number"
            el.isEditable && combined.containsAny("phone", "mobile", "contact number") -> "phone-input"
            // Email field — hint "Email", "Email address"
            el.isEditable && combined.containsAny("email", "e-mail", "email address") && !combined.contains("password") -> "email-input"
            // Username / login id field
            el.isEditable && combined.containsAny("username", "user name", "user id", "login id", "account id") -> "username-input"
            el.isEditable && combined.containsAny("search", "find", "query") -> "search-input"
            el.isEditable && combined.containsAny("message", "reply", "chat", "compose") -> "message-input"
            el.isEditable && combined.containsAny("to", "recipient") -> "recipient-input"
            el.isEditable && combined.containsAny("amount", "price", "number") -> "number-input"
            el.isEditable -> "text-input"

            // Search icon (small, near top, has search label)
            combined.containsAny("search", "find") && el.width < 160 && el.centerY < 400 -> "search-icon"

            // Navigation elements
            combined.containsAny("back", "navigate up", "arrow_back") -> "back-button"
            // Bottom nav Home tab — two detection paths:
            //   1. Label contains "home"/"bottom_nav" AND element is below 1500px (pixel threshold)
            //   2. Element text is literally "Home" and it's not an editable field
            //      (catches large screens where 1500px cutoff is too low)
            (combined.containsAny("home", "bottom_nav") && el.centerY > 1500)
                || (el.text.lowercase() == "home" && !el.isEditable) -> "nav-home"
            combined.containsAny("profile", "account", "me") && el.centerY > 1500 -> "nav-profile"

            // Action buttons
            combined.containsAny("send", "submit", "post", "publish") -> "send-button"
            combined.containsAny("add to cart", "buy", "order", "book") -> "purchase-button"
            combined.containsAny("confirm", "proceed", "continue", "next") -> "confirm-button"
            combined.containsAny("cancel", "close", "dismiss", "not now") -> "dismiss-button"

            // Content items
            el.isCheckable -> "toggle-switch"
            el.isClickable && el.width > el.height * 3 && el.width > 400 -> "list-item"
            el.isClickable && el.width > 600 -> "wide-button"
            el.isClickable -> "button"

            else -> el.type.ifBlank { "view" }
        }
    }

    // ─── Index Resolution ─────────────────────────────────────────────────────

    /** Find a safe index to tap — redirect voice elements to nearest text input. */
    private fun findSafeTapIndex(elements: List<ScreenElement>, requestedIdx: Int): Int {
        if (requestedIdx !in elements.indices) return requestedIdx
        val el = elements[requestedIdx]
        // If AI tried to tap a voice element, redirect to the best text input instead
        if (elementRole(el) == "voice") {
            return findBestInputIndex(elements, -1)
        }
        return requestedIdx
    }

    /** Find the best editable input field at the given index, or the highest-scoring one. */
    private fun findBestInputIndex(elements: List<ScreenElement>, preferredIdx: Int): Int {
        if (preferredIdx in elements.indices && elements[preferredIdx].isEditable) return preferredIdx

        return elements.indices
            .filter { i -> elements[i].isEditable }
            .maxByOrNull { i ->
                val el = elements[i]
                val role = elementRole(el)
                var score = 0
                if (role == "search-input") score += 200
                if (role == "message-input") score += 180
                if (role.endsWith("-input")) score += 100
                if (el.width > 500) score += 80
                score
            } ?: preferredIdx
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun bestLabel(el: ScreenElement): String {
        return listOf(
            el.text,
            el.hint,
            el.contentDescription,
            el.viewId.substringAfterLast('/').replace('_', ' '),
        ).firstOrNull { it.isNotBlank() } ?: ""
    }

    private fun posTag(el: ScreenElement): String {
        val h = when {
            el.centerX < 360 -> "left"
            el.centerX > 720 -> "right"
            else -> "center"
        }
        val v = when {
            el.centerY < 320 -> "top"
            el.centerY > 1500 -> "bottom"
            else -> "mid"
        }
        return "$v-$h"
    }

    private fun sizeTag(el: ScreenElement): String = when {
        el.width > 650 -> "wide"
        el.width > 320 -> "medium"
        else -> "small"
    }

    private fun String.containsAny(vararg terms: String): Boolean =
        terms.any { this.contains(it) }

    /**
     * Word-boundary contains — checks that each given word appears as a complete token,
     * NOT as a substring of a longer word. The string is split on any non-alphanumeric
     * character so we get a real word list before comparing.
     *
     * Examples:
     *   "comic book".containsWord("mic")   → false  ("mic" is inside "comic", not a token)
     *   "open mic tonight".containsWord("mic") → true  ("mic" is a separate token)
     *   "invoice paid".containsWord("voice") → false  ("voice" is inside "invoice")
     *   "voice search".containsWord("voice") → true
     *   "my speaker".containsWord("speak")  → false
     *   "speak now".containsWord("speak")   → true
     *
     * Use this for short ambiguous terms where containsAny() would false-match.
     */
    private fun String.containsWord(vararg words: String): Boolean {
        val tokens = this.split(Regex("[^a-z0-9]")).filter { it.isNotEmpty() }
        return words.any { word -> tokens.contains(word) }
    }

    /**
     * Returns true if the given package name looks like the device home screen / launcher.
     * We use this to disable the drift guard when the agent starts on the home screen —
     * otherwise it would lock the AI to the launcher and re-open it every time the AI
     * navigates to any real app (GeneralSkill cross-app tasks).
     *
     * Android launcher packages universally contain "launcher" or ".home" in their name.
     * Samsung: com.samsung.android.app.launcher
     * MIUI:    com.miui.home
     * Stock:   com.android.launcher3, com.google.android.apps.nexuslauncher
     * OnePlus: com.oneplus.launcher
     */
    private fun isLauncherPackage(pkg: String): Boolean {
        if (pkg.isBlank()) return true  // blank = unknown = treat as home
        val lower = pkg.lowercase()
        return lower.contains("launcher") ||
               lower.contains(".home") ||
               lower.contains("nexuslauncher") ||
               lower == "com.android.systemui"
    }

    // ─── JSON Parsing ─────────────────────────────────────────────────────────

    private fun parseAction(response: String): ScreenAction {
        return try {
            val cleaned = response.replace("```json", "").replace("```", "").trim()
            val jsonStr = extractJsonObject(cleaned)
                ?: return ScreenAction("fail", summary = "No JSON in: ${response.take(60)}")
            val json = JsonParser.parseString(jsonStr).asJsonObject

            // ReAct format support — observation + thought fields are logged as summary
            // This forces the AI to describe what it sees before acting (from AppAgent research)
            val observation = json.get("observation")?.asString ?: ""
            val thought = json.get("thought")?.asString ?: ""
            val explicitSummary = json.get("summary")?.asString ?: ""
            val summary = when {
                explicitSummary.isNotBlank() -> explicitSummary
                thought.isNotBlank() -> thought
                observation.isNotBlank() -> observation
                else -> ""
            }

            ScreenAction(
                action = json.get("action")?.asString ?: "wait",
                index = json.get("index")?.asInt ?: -1,
                text = json.get("text")?.asString ?: "",
                summary = summary,
            )
        } catch (_: Exception) {
            ScreenAction("fail", summary = "Parse error: ${response.take(60)}")
        }
    }

    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start == -1) return null
        var depth = 0
        var inString = false
        var escaping = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaping -> escaping = false
                c == '\\' && inString -> escaping = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return if (depth > 0) text.substring(start) + "}".repeat(depth) else null
    }

    // ─── Set-of-Mark Vision ──────────────────────────────────────────────────

    /**
     * Annotate a screenshot with numbered colored boxes on each element.
     * This is the core technique used by AppAgent (Tencent) and UFO (Microsoft).
     * The AI sees the ACTUAL VISUAL UI — it can distinguish mic icon vs search field,
     * see button shapes, icon graphics, and layout — not just text labels.
     */
    private fun annotateScreenshot(bitmap: Bitmap, elements: List<ScreenElement>): Bitmap {
        val soft = if (bitmap.config == Bitmap.Config.HARDWARE)
            bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        val annotated = soft.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(annotated)

        val boxPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; isAntiAlias = true }
        val bgPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
        val textPaint = Paint().apply {
            color = Color.WHITE; textSize = 26f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true
        }

        // Distinct colors for different element types — makes it easy for AI to read
        val roleColors = mapOf(
            "search-input"   to Color.rgb(0, 200, 100),   // green — safe to type here
            "message-input"  to Color.rgb(0, 180, 255),   // blue — message field
            "send-button"    to Color.rgb(255, 140, 0),   // orange — action buttons
            "voice"          to Color.rgb(200, 0, 0),     // red — NEVER tap
            "search-icon"    to Color.rgb(255, 220, 0),   // yellow — tap to open search
            "list-item"      to Color.rgb(180, 100, 255), // purple — content rows
            "toggle-switch"  to Color.rgb(0, 200, 200),   // teal — toggles
        )
        val defaultColor = Color.rgb(100, 160, 255) // light blue for everything else

        elements.forEachIndexed { i, el ->
            val role = elementRole(el)
            if (role == "voice") return@forEachIndexed // skip drawing voice elements — don't highlight them

            val color = roleColors[role] ?: defaultColor
            boxPaint.color = color
            bgPaint.color = color

            val halfW = (el.width / 2).coerceAtLeast(20)
            val halfH = (el.height / 2).coerceAtLeast(20)
            val left   = (el.centerX - halfW).toFloat().coerceAtLeast(0f)
            val top    = (el.centerY - halfH).toFloat().coerceAtLeast(0f)
            val right  = (el.centerX + halfW).toFloat().coerceAtMost(annotated.width.toFloat())
            val bottom = (el.centerY + halfH).toFloat().coerceAtMost(annotated.height.toFloat())

            canvas.drawRect(left, top, right, bottom, boxPaint)

            // Number badge in top-left corner of the box
            val badgeSize = 34f
            canvas.drawRect(left, top, left + badgeSize, top + badgeSize, bgPaint)
            canvas.drawText("$i", left + 5f, top + 25f, textPaint)
        }
        return annotated
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        val soft = if (bitmap.config == Bitmap.Config.HARDWARE)
            bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        soft.compress(Bitmap.CompressFormat.JPEG, 82, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    // ─── AI Calls ────────────────────────────────────────────────────────────

    private suspend fun callAI(prompt: String): String? = when (provider) {
        AIProvider.GEMINI -> callGemini(prompt)
        AIProvider.CLAUDE -> callClaude(prompt)
        AIProvider.OPENAI -> callOpenAI(prompt)
    }

    /**
     * Vision-enhanced AI call — sends annotated screenshot + text prompt.
     * Only Gemini supports vision in our current setup (free tier).
     * Falls back to text-only if screenshot not available.
     */
    private suspend fun callAIWithVision(prompt: String, annotatedBitmap: Bitmap?): String? {
        if (annotatedBitmap == null || provider != AIProvider.GEMINI) return callAI(prompt)
        return callGeminiVision(prompt, annotatedBitmap)
    }

    private suspend fun callGemini(prompt: String): String? {
        if (stopRequested) return null // fast-path: don't even start the call
        val modelName = model.ifBlank { AIBrain.detectFastestModel(apiKey) }
        val body = gson.toJson(mapOf(
            "contents" to listOf(mapOf(
                "role" to "user",
                "parts" to listOf(mapOf("text" to prompt)),
            )),
            "generationConfig" to mapOf(
                "temperature" to 0.0,
                "responseMimeType" to "application/json",
                "thinkingConfig" to mapOf("thinkingBudget" to 0),
            ),
        ))
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
        return try {
            withContext(Dispatchers.IO) {
                val call = client.newCall(
                    Request.Builder().url(url)
                        .addHeader("content-type", "application/json")
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()
                )
                activeCall = call // store so requestStop() can cancel it mid-call
                call.execute().use { resp ->
                    val raw = resp.body?.string() ?: return@withContext null
                    val json = JsonParser.parseString(raw).asJsonObject
                    if (json.has("error")) return@withContext null
                    json.getAsJsonArray("candidates")
                        ?.get(0)?.asJsonObject
                        ?.getAsJsonObject("content")
                        ?.getAsJsonArray("parts")
                        ?.get(0)?.asJsonObject
                        ?.get("text")?.asString?.trim()
                }
            }
        } catch (_: Exception) { null
        } finally { activeCall = null }
    }

    /**
     * Gemini vision call — sends the annotated screenshot + prompt.
     * Gemini sees the ACTUAL screen visually with numbered boxes on each element.
     * This is how AppAgent/UFO distinguish mic icon from search field — they LOOK at it.
     */
    private suspend fun callGeminiVision(prompt: String, bitmap: Bitmap): String? {
        if (stopRequested) return null
        val modelName = model.ifBlank { AIBrain.detectFastestModel(apiKey) }
        val imageBase64 = bitmapToBase64(bitmap)
        val body = gson.toJson(mapOf(
            "contents" to listOf(mapOf(
                "parts" to listOf(
                    mapOf("text" to prompt),
                    mapOf("inline_data" to mapOf(
                        "mime_type" to "image/jpeg",
                        "data" to imageBase64,
                    )),
                ),
            )),
            "generationConfig" to mapOf(
                "temperature" to 0.0,
                "responseMimeType" to "application/json",
                "thinkingConfig" to mapOf("thinkingBudget" to 0),
            ),
        ))
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
        return try {
            withContext(Dispatchers.IO) {
                val call = client.newCall(
                    Request.Builder().url(url)
                        .addHeader("content-type", "application/json")
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()
                )
                activeCall = call
                call.execute().use { resp ->
                    val raw = resp.body?.string() ?: return@withContext null
                    val json = JsonParser.parseString(raw).asJsonObject
                    if (json.has("error")) return@withContext null
                    json.getAsJsonArray("candidates")
                        ?.get(0)?.asJsonObject
                        ?.getAsJsonObject("content")
                        ?.getAsJsonArray("parts")
                        ?.get(0)?.asJsonObject
                        ?.get("text")?.asString?.trim()
                }
            }
        } catch (_: Exception) { null
        } finally { activeCall = null }
    }

    private suspend fun callClaude(prompt: String): String? {
        if (stopRequested) return null
        val modelName = model.ifBlank { AIBrain.detectFastestModel(apiKey) }
        val body = gson.toJson(mapOf(
            "model" to modelName,
            "max_tokens" to 512,
            "system" to "You are a phone automation agent. Reply with valid JSON only. No explanation.",
            "messages" to listOf(mapOf("role" to "user", "content" to prompt)),
        ))
        return try {
            withContext(Dispatchers.IO) {
                val call = client.newCall(
                    Request.Builder().url("https://api.anthropic.com/v1/messages")
                        .addHeader("x-api-key", apiKey)
                        .addHeader("anthropic-version", "2023-06-01")
                        .addHeader("content-type", "application/json")
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()
                )
                activeCall = call
                call.execute().use { resp ->
                    val raw = resp.body?.string() ?: return@withContext null
                    val json = JsonParser.parseString(raw).asJsonObject
                    json.getAsJsonArray("content")
                        ?.get(0)?.asJsonObject
                        ?.get("text")?.asString?.trim()
                }
            }
        } catch (_: Exception) { null
        } finally { activeCall = null }
    }

    private suspend fun callOpenAI(prompt: String): String? {
        if (stopRequested) return null
        val modelName = model.ifBlank { AIBrain.detectFastestModel(apiKey) }
        val body = gson.toJson(mapOf(
            "model" to modelName,
            "response_format" to mapOf("type" to "json_object"),
            "temperature" to 0,
            "messages" to listOf(
                mapOf("role" to "system", "content" to "Phone automation agent. Reply JSON only."),
                mapOf("role" to "user", "content" to prompt),
            ),
        ))
        return try {
            withContext(Dispatchers.IO) {
                val call = client.newCall(
                    Request.Builder().url("https://api.openai.com/v1/chat/completions")
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("content-type", "application/json")
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()
                )
                activeCall = call
                call.execute().use { resp ->
                    val raw = resp.body?.string() ?: return@withContext null
                    val json = JsonParser.parseString(raw).asJsonObject
                    json.getAsJsonArray("choices")
                        ?.get(0)?.asJsonObject
                        ?.getAsJsonObject("message")
                        ?.get("content")?.asString?.trim()
                }
            }
        } catch (_: Exception) { null
        } finally { activeCall = null }
    }
}
