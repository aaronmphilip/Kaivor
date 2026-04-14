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
) {
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
        // Clear overlays before starting
        repeat(2) {
            dismissVoiceOverlay(runner)
            dismissObstructingPopups(runner)
            delay(100)
        }

        // Build contextual goal:
        // 1. Tell AI that app may be in a leftover state from previous use (very common issue)
        // 2. Inject user's learned preferences so the AI behaves the way they want
        val memoryContext = userMemory?.buildPromptContext() ?: ""
        val contextualGoal = buildString {
            append(goal)
            appendLine()
            appendLine()
            appendLine("⚠️ APP STATE NOTE: The app may already be open from a previous task.")
            appendLine("If the current screen shows unrelated content (old search, open chat, video playing, restaurant page, etc.),")
            appendLine("navigate back to the app's home/main screen first — then start this task fresh.")
            if (memoryContext.isNotBlank()) {
                appendLine()
                appendLine("📌 USER PREFERENCES (learned from past interactions — follow these):")
                appendLine(memoryContext)
            }
        }

        // Generate a plan
        val initialScreen = try { runner.readScreen().take(900) } catch (_: Exception) { "" }
        val initialElements = runner.getClickableElements()
        val plan = generatePlan(contextualGoal, initialScreen, describeElements(initialElements))

        val actionLog = mutableListOf<String>()
        var consecutiveBlockedOrMiss = 0

        // ── OpenClaw-style stuck detection (AppAgent/DroidClaw research) ─────────
        // Rolling window of screen state hashes — if unchanged for 3 steps, inject recovery hint
        val recentScreenHashes = ArrayDeque<Int>(5)
        var lastTappedIdx = -1
        var repeatTapCount = 0

        for (step in 1..maxSteps) {
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
                    appendLine("TRY SOMETHING DIFFERENT: press back, scroll a new direction, tap a different element, or use dismiss.")
                }
                if (isRepeatTapping) {
                    appendLine("🚨 REPEAT TAP: Element #$lastTappedIdx has been tapped $repeatTapCount times with no result. STOP tapping it.")
                    appendLine("Choose a DIFFERENT element or a completely different action.")
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

            // Track tap repetition — if AI keeps tapping same element, flag it next step
            if (action.action == "tap") {
                if (action.index == lastTappedIdx) repeatTapCount++
                else { lastTappedIdx = action.index; repeatTapCount = 1 }
            } else if (action.action !in listOf("wait", "dismiss")) {
                repeatTapCount = 0 // reset on any real action
            }

            val result = executeAction(runner, action, elements)

            when (result) {
                "DONE" -> return action.summary.ifBlank { "Done." }
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

        val finalScreen = try { runner.readScreen().take(500) } catch (_: Exception) { "" }
        return "Reached step limit ($maxSteps).\n$finalScreen"
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
            appendLine("1. Is specific about what to look for (element text, type, position)")
            appendLine("2. States the action clearly (tap, type, scroll, enter, wait)")
            appendLine("3. Includes how to verify each step succeeded")
            appendLine("4. For search tasks: always do search-field → type → submit → scroll → pick result")
            appendLine("5. NEVER recommends voice/mic/audio buttons")
            appendLine("6. Works on ANY app — no app-specific assumptions")
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
        val voiceIndicators = listOf(
            "speak now", "listening", "search by voice", "try saying",
            "voice search", "say something", "microphone active",
            "voice input", "listening to you",
        )
        if (voiceIndicators.any { screen.contains(it) }) {
            runner.pressBack()
            delay(280)
        }
    }

    private suspend fun dismissObstructingPopups(runner: SandboxedRunner) {
        try {
            val screen = runner.readScreen().lowercase()

            // Comprehensive popup signal list covering Indian apps (Zomato, Swiggy, PhonePe etc.)
            // and generic Android popups. If ANY of these appear on screen we try to dismiss.
            val popupSignals = listOf(
                // Generic Android
                "not now", "maybe later", "skip", "got it", "no thanks", "no, thanks",
                "dismiss", "remind me later", "allow", "deny", "update available",
                "rate this app", "new feature", "don't allow", "block",
                // Notifications / location (VERY common in Zomato, Swiggy, PhonePe)
                "enable notifications", "turn on notifications", "allow notifications",
                "enable location", "allow location", "use my location", "set location",
                "share location", "location permission",
                // App store / update
                "update now", "update app", "rate us", "rate app", "rate on play store",
                "rate now", "review app", "leave a review",
                // Food delivery specific
                "claim offer", "view offer", "see offers", "grab offer",
                "explore restaurants", "start ordering",
                // Payment apps
                "set upi pin", "complete your profile", "verify your number",
                "complete kyc", "finish setup",
                // Onboarding overlays
                "get started", "explore", "take a tour", "show me around",
            )

            if (popupSignals.any { screen.contains(it) }) {
                // First try the standard dismiss list (buttons like "Skip", "Not now", etc.)
                val dismissed = runner.dismissPopups(2)
                if (dismissed == 0) {
                    // Nothing matched — try pressing back to close the modal
                    runner.pressBack()
                }
                delay(200)
            }
        } catch (_: Exception) { /* ignore */ }
    }

    // ─── Action Execution ─────────────────────────────────────────────────────

    private suspend fun executeAction(
        runner: SandboxedRunner,
        action: ScreenAction,
        elements: List<ScreenElement>,
    ): String {
        return when (action.action) {

            "tap" -> {
                val idx = findSafeTapIndex(elements, action.index)
                if (idx !in elements.indices) {
                    return "tap[${action.index}] SKIP — index out of range"
                }
                val el = elements[idx]
                val role = elementRole(el)

                // Block voice/audio elements universally
                if (role == "voice") {
                    return "tap[$idx] BLOCKED — voice/mic element, skipping"
                }

                val ok = runner.tapAtPoint(el.centerX.toFloat(), el.centerY.toFloat())
                delay(300)
                val label = bestLabel(el).take(30).ifBlank { role }
                val idxLabel = if (idx == action.index) "$idx" else "${action.index}→$idx"
                "tap[$idxLabel] '$label' → ${if (ok) "OK" else "MISS"}"
            }

            "type" -> {
                // Find the best input field — prefer the one at the given index, fall back to best editable
                val typeIdx = findBestInputIndex(elements, action.index)
                if (typeIdx in elements.indices) {
                    val el = elements[typeIdx]
                    runner.tapAtPoint(el.centerX.toFloat(), el.centerY.toFloat())
                    delay(150)
                }
                val ok = runner.typeInFocused(action.text)
                    || runner.typeInBestField(action.text, "search", "find", "message", "to", "query")
                delay(180)
                val idxLabel = if (typeIdx in elements.indices) "$typeIdx" else "auto"
                "type[$idxLabel] '${action.text.take(30)}' → ${if (ok) "OK" else "MISS"}"
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
                runner.pressBack()
                delay(220)
                "back"
            }

            "home" -> {
                runner.goHome()
                delay(260)
                "home"
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
            appendLine("═══ UNIVERSAL UI RULES ═══")
            appendLine("1. IDENTIFY SCREEN TYPE first:")
            appendLine("   Home/feed → navigate to search. Search open → type (green), not mic (red).")
            appendLine("   Results → scroll to target then tap. Detail → take requested action.")
            appendLine("   Compose → fill all fields then submit. Settings → find toggle.")
            appendLine("2. Search: always use TEXT INPUT field (wide, editable, green) — NEVER the mic icon.")
            appendLine("3. Voice overlay ('Speak now', 'Listening') → use action=back immediately.")
            appendLine("4. After typing → action=enter. Never retype already-typed text.")
            appendLine("5. BOTTOM NAV BAR (Home/Shorts/Library tabs at very bottom of screen, pos=bottom):")
            appendLine("   DO NOT tap bottom nav unless you INTEND to change section. Content items are in mid/top area.")
            appendLine("6. If same element failed twice → try different approach (scroll, back, different element).")
            appendLine("7. done only when you CONFIRM success is visible on screen.")
            appendLine("8. fail only if truly stuck with absolutely no path forward.")
            appendLine()
            appendLine("ACTIONS:")
            appendLine("""tap:{"action":"tap","index":N}  type:{"action":"type","index":N,"text":"X"}  enter:{"action":"enter"}""")
            appendLine("""scroll_down:{"action":"scroll_down"}  scroll_up:{"action":"scroll_up"}  back:{"action":"back"}""")
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

        // Voice/mic — always marked, AI will avoid these
        if (combined.containsAny("mic", "voice", "microphone", "speak now", "listening",
                "audio_search", "voice_search", "speak")) {
            return "voice"
        }

        return when {
            // Editable fields first (most specific)
            el.isEditable && combined.containsAny("search", "find", "query") -> "search-input"
            el.isEditable && combined.containsAny("message", "reply", "chat", "compose") -> "message-input"
            el.isEditable && combined.containsAny("to", "recipient", "email") -> "recipient-input"
            el.isEditable && combined.containsAny("amount", "price", "number") -> "number-input"
            el.isEditable -> "text-input"

            // Search icon (small, near top, has search label)
            combined.containsAny("search", "find") && el.width < 160 && el.centerY < 400 -> "search-icon"

            // Navigation elements
            combined.containsAny("back", "navigate up", "arrow_back") -> "back-button"
            combined.containsAny("home", "bottom_nav") && el.centerY > 1500 -> "nav-home"
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
                client.newCall(
                    Request.Builder().url(url)
                        .addHeader("content-type", "application/json")
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute().use { resp ->
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
        } catch (_: Exception) { null }
    }

    /**
     * Gemini vision call — sends the annotated screenshot + prompt.
     * Gemini sees the ACTUAL screen visually with numbered boxes on each element.
     * This is how AppAgent/UFO distinguish mic icon from search field — they LOOK at it.
     */
    private suspend fun callGeminiVision(prompt: String, bitmap: Bitmap): String? {
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
                client.newCall(
                    Request.Builder().url(url)
                        .addHeader("content-type", "application/json")
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute().use { resp ->
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
        } catch (_: Exception) { null }
    }

    private suspend fun callClaude(prompt: String): String? {
        val modelName = model.ifBlank { AIBrain.detectFastestModel(apiKey) }
        val body = gson.toJson(mapOf(
            "model" to modelName,
            "max_tokens" to 512,
            "system" to "You are a phone automation agent. Reply with valid JSON only. No explanation.",
            "messages" to listOf(mapOf("role" to "user", "content" to prompt)),
        ))
        return try {
            withContext(Dispatchers.IO) {
                client.newCall(
                    Request.Builder().url("https://api.anthropic.com/v1/messages")
                        .addHeader("x-api-key", apiKey)
                        .addHeader("anthropic-version", "2023-06-01")
                        .addHeader("content-type", "application/json")
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute().use { resp ->
                    val raw = resp.body?.string() ?: return@withContext null
                    val json = JsonParser.parseString(raw).asJsonObject
                    json.getAsJsonArray("content")
                        ?.get(0)?.asJsonObject
                        ?.get("text")?.asString?.trim()
                }
            }
        } catch (_: Exception) { null }
    }

    private suspend fun callOpenAI(prompt: String): String? {
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
                client.newCall(
                    Request.Builder().url("https://api.openai.com/v1/chat/completions")
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("content-type", "application/json")
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute().use { resp ->
                    val raw = resp.body?.string() ?: return@withContext null
                    val json = JsonParser.parseString(raw).asJsonObject
                    json.getAsJsonArray("choices")
                        ?.get(0)?.asJsonObject
                        ?.getAsJsonObject("message")
                        ?.get("content")?.asString?.trim()
                }
            }
        } catch (_: Exception) { null }
    }
}
