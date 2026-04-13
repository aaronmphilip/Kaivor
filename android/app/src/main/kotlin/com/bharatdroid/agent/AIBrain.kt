package com.bharatdroid.agent

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

data class SkillStep(
    val skillId: String,
    val params: Map<String, Any> = emptyMap(),
)

data class AgentPlan(
    val type: PlanType,
    val skillId: String? = null,
    val params: Map<String, Any> = emptyMap(),
    val directReply: String? = null,
    val steps: List<SkillStep> = emptyList(),
)

enum class PlanType { RUN_SKILL, MULTI_STEP, DIRECT_REPLY, UNKNOWN }

// ─────────────────────────────────────────────
// AI PROVIDER — supports Claude, Gemini (free), AND OpenAI
// ─────────────────────────────────────────────

enum class AIProvider { CLAUDE, GEMINI, OPENAI }

class AIBrain(
    private val apiKey: String,
    private val availableSkills: List<SkillInfo>,
    private val provider: AIProvider = detectProvider(apiKey),
    private val model: String = "",
) {
    data class SkillInfo(val id: String, val description: String, val exampleParams: String)

    companion object {
        fun detectProvider(key: String): AIProvider = when {
            key.startsWith("sk-ant-") -> AIProvider.CLAUDE
            key.startsWith("AIza")    -> AIProvider.GEMINI
            key.startsWith("sk-")     -> AIProvider.OPENAI
            else                      -> AIProvider.GEMINI // Default to free option
        }

        /**
         * Auto-select the fastest model for the given API key.
         * Called by both AIBrain and ScreenAgent so model choice is a single source of truth.
         *
         * Gemini  → gemini-2.5-flash  (best speed + reasoning on free quota)
         * Claude  → claude-haiku-4-5  (fastest Claude tier)
         * OpenAI  → gpt-4o-mini       (fastest + cheapest GPT)
         */
        fun detectFastestModel(key: String): String = when (detectProvider(key)) {
            AIProvider.GEMINI -> "gemini-2.5-flash"
            AIProvider.CLAUDE -> "claude-haiku-4-5-20251001"
            AIProvider.OPENAI -> "gpt-4o-mini"
        }
    }

    private val client = OkHttpClient()
    private val gson = Gson()
    private val history = mutableMapOf<Long, ArrayDeque<Map<String, String>>>()

    private val systemPrompt: String get() {
        val skillsList = availableSkills.joinToString("\n") { s ->
            "- ${s.id}: ${s.description}. Example params: ${s.exampleParams}"
        }
        return """
You are BharatClaw — an AI agent running directly on an Android phone in India.
You control Android apps on the user's behalf via Telegram commands.

Available skills:
$skillsList

Rules:
1. SINGLE task — return: {"skill": "<skill_id>", "params": {<key-value pairs>}}
2. MULTI-STEP task (user wants multiple things done) — return: {"steps": [{"skill":"<id>","params":{...}}, {"skill":"<id>","params":{...}}]}
3. Just chatting / question — return: {"reply": "<your answer>"}
4. Always reply in the same language the user used (Hindi or English).
5. Be concise. Do not explain — just do it.
6. IMPORTANT: If no specific skill matches, use "general" skill with "goal" param. NEVER say "I don't have a skill for that".
7. YouTube ROUTING (CRITICAL):
   - SIMPLE: "play X song" OR "play X video" → {"skill":"youtube","params":{"action":"play","query":"X"}}
   - COMPLEX: ANYTHING involving: go to, channel, navigate, visit, browse, like, subscribe, multiple videos/channels → {"skill":"youtube","params":{"action":"goal","goal":"<FULL user intent here>"}}
   - Examples: "go to ValueEntainment" = goal. "navigate to that channel" = goal. "like the top 2 videos" = goal. "subscribe to channels" = goal.
   - RULE: If user says multiple actions or "go to" or "channel" or "like" or "subscribe to multiple" → ALWAYS use action="goal", pass complete user intent as "goal" param.
8. WhatsApp (CRITICAL):
   - ALWAYS extract contact name PRECISELY from message. If user says "message John", contact="John" (NEVER default to Papa or others).
   - ALWAYS use action="send"
   - Required: "contact" (exact name from user), "message" (exact text from user)
   - RULE: If no contact name found in message, ask user: "Who should I message?" Do NOT assume or default to previous contacts.
9. Shopping (Amazon/Flipkart): "action":"search" for simple search, "action":"goal" with "goal" param for complex tasks (compare, checkout, track order, etc.).
10. Chrome: "action":"search" for simple search, "action":"goal" with "goal" and optionally "url" for multi-step web tasks (fill forms, click links, etc.). Always wait for pages to load before acting.
11. Match intent precisely. "Play X on YouTube" = youtube. "Search X on Amazon" = amazon.
12. Gmail: "read" inbox, "compose" email (needs "to","subject","body"), "search" to find.
13. Calendar: "today" schedule, "create" events (needs "title"), "week" for weekly view.
14. Notes: "create" with "title"+"content", "read" to list, "search" to find.
15. Contacts: "search" with "query", "call" with "contact", "history" for recent.
16. Files: "browse", "search", "open", "downloads".
17. Settings: "section" param: wifi, bluetooth, battery, storage, display, sound, notifications, about.
18. Screen Reader: "read" current screen, "scroll_and_read" for long pages, "find" text, "tap" text.
19. Reading screen/documents/invoices = "screen" skill.
20. Unknown app = "general" skill with descriptive "goal" param.
22. Instagram: "action":"search"/"reels"/"dm"/"home" for simple tasks. "action":"goal" with "goal" param for complex tasks (follow, like, comment, post, etc.)
23. Swiggy/Zomato: "action":"order" for ordering, "action":"goal" with "goal" for complex flows.
24. Maps: "action":"navigate"/"search"/"directions" for simple tasks, "action":"goal" for complex map tasks.
21. MULTI-STEP examples: "Play a song then message mom" → steps: youtube + whatsapp. "Order pizza and check email" → steps: zomato + gmail. Always break complex requests into ordered steps.

Do NOT return anything outside of JSON. No markdown, no prose.
        """.trimIndent()
    }

    suspend fun process(chatId: Long, userMessage: String): AgentPlan {
        val chatHistory = history.getOrPut(chatId) { ArrayDeque() }
        chatHistory.addLast(mapOf("role" to "user", "content" to userMessage))

        val result = when (provider) {
            AIProvider.CLAUDE -> callClaude(chatHistory)
            AIProvider.GEMINI -> callGemini(chatHistory)
            AIProvider.OPENAI -> callOpenAI(chatHistory)
        }

        if (result.isFailure) {
            return AgentPlan(PlanType.DIRECT_REPLY, directReply = "AI error: ${result.exceptionOrNull()?.message ?: "Unknown error"}")
        }

        val assistantText = result.getOrNull()
        if (assistantText == null) {
            return AgentPlan(PlanType.DIRECT_REPLY, directReply = "AI returned empty response. Check your API key and try again.")
        }

        chatHistory.addLast(mapOf("role" to "assistant", "content" to assistantText))
        while (chatHistory.size > 10) chatHistory.removeFirst()

        return parseResponse(assistantText)
    }

    // ── Claude API ────────────────────────────

    private suspend fun callClaude(chatHistory: ArrayDeque<Map<String, String>>): Result<String?> {
        val modelName = model.ifBlank { detectFastestModel(apiKey) }
        val messages = chatHistory.map { gson.toJson(it) }
        val messagesJson = "[${messages.joinToString(",")}]"

        val requestBody = """
            {
                "model": "$modelName",
                "max_tokens": 2048,
                "system": ${gson.toJson(systemPrompt)},
                "messages": $messagesJson
            }
        """.trimIndent()

        val rawResponse = try {
            withContext(Dispatchers.IO) {
                client.newCall(
                    Request.Builder()
                        .url("https://api.anthropic.com/v1/messages")
                        .addHeader("x-api-key", apiKey)
                        .addHeader("anthropic-version", "2023-06-01")
                        .addHeader("content-type", "application/json")
                        .post(requestBody.toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute().use { it.body?.string() }
            } ?: return Result.failure(Exception("Empty response from Claude API"))
        } catch (e: Exception) {
            return Result.failure(Exception("Network error calling Claude: ${e.message}"))
        }

        return try {
            val json = JsonParser.parseString(rawResponse).asJsonObject
            if (json.has("error")) {
                val errMsg = json.getAsJsonObject("error")?.get("message")?.asString ?: rawResponse
                return Result.failure(Exception("Claude API: $errMsg"))
            }
            val text = json.getAsJsonArray("content")
                .get(0).asJsonObject
                .get("text").asString
                .trim()
            Result.success(text)
        } catch (e: Exception) {
            Result.failure(Exception("Claude response parse error. Raw: ${rawResponse.take(300)}"))
        }
    }

    // ── Gemini API (FREE) ─────────────────────

    private suspend fun callGemini(chatHistory: ArrayDeque<Map<String, String>>): Result<String?> {
        val modelName = model.ifBlank { detectFastestModel(apiKey) }

        // Build contents from chat history only (no fake handshake needed)
        val contents = chatHistory.map { msg ->
            val role = if (msg["role"] == "user") "user" else "model"
            mapOf(
                "role" to role,
                "parts" to listOf(mapOf("text" to (msg["content"] ?: "")))
            )
        }

        val requestBody = gson.toJson(mapOf(
            // systemInstruction is the proper Gemini API field — faster than fake user/model turns
            "systemInstruction" to mapOf(
                "parts" to listOf(mapOf("text" to systemPrompt))
            ),
            "contents" to contents,
            "generationConfig" to mapOf(
                "temperature" to 0.0,
                "responseMimeType" to "application/json",
                // Disable extended thinking — we need instant JSON, not deep reasoning
                "thinkingConfig" to mapOf("thinkingBudget" to 0),
            )
        ))

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"

        val rawResponse = try {
            withContext(Dispatchers.IO) {
                client.newCall(
                    Request.Builder()
                        .url(url)
                        .addHeader("content-type", "application/json")
                        .post(requestBody.toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute().use { it.body?.string() }
            } ?: return Result.failure(Exception("Empty response from Gemini API"))
        } catch (e: Exception) {
            return Result.failure(Exception("Network error calling Gemini: ${e.message}"))
        }

        return try {
            val json = JsonParser.parseString(rawResponse).asJsonObject
            if (json.has("error")) {
                val errObj = json.getAsJsonObject("error")
                val errMsg = errObj?.get("message")?.asString ?: rawResponse.take(300)
                val errStatus = errObj?.get("status")?.asString ?: ""
                return Result.failure(Exception("Gemini API [$errStatus]: $errMsg"))
            }
            val text = json.getAsJsonArray("candidates")
                .get(0).asJsonObject
                .getAsJsonObject("content")
                .getAsJsonArray("parts")
                .get(0).asJsonObject
                .get("text").asString
                .trim()
            Result.success(text)
        } catch (e: Exception) {
            Result.failure(Exception("Gemini response parse error. Raw: ${rawResponse.take(300)}"))
        }
    }

    // ── OpenAI API (ChatGPT) ──────────────────

    private suspend fun callOpenAI(chatHistory: ArrayDeque<Map<String, String>>): Result<String?> {
        val modelName = model.ifBlank { detectFastestModel(apiKey) }

        val messages = mutableListOf<Map<String, Any>>()
        // System message
        messages.add(mapOf("role" to "system", "content" to systemPrompt))
        // Chat history
        chatHistory.forEach { msg ->
            messages.add(mapOf("role" to (msg["role"] ?: "user"), "content" to (msg["content"] ?: "")))
        }

        val requestBody = gson.toJson(mapOf(
            "model" to modelName,
            "temperature" to 0.1,
            "messages" to messages,
            "response_format" to mapOf("type" to "json_object"),
            // No max_tokens limit — JSON mode keeps output concise naturally
        ))

        val rawResponse = try {
            withContext(Dispatchers.IO) {
                client.newCall(
                    Request.Builder()
                        .url("https://api.openai.com/v1/chat/completions")
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("content-type", "application/json")
                        .post(requestBody.toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute().use { it.body?.string() }
            } ?: return Result.failure(Exception("Empty response from OpenAI API"))
        } catch (e: Exception) {
            return Result.failure(Exception("Network error calling OpenAI: ${e.message}"))
        }

        return try {
            val json = JsonParser.parseString(rawResponse).asJsonObject
            if (json.has("error")) {
                val errMsg = json.getAsJsonObject("error")?.get("message")?.asString ?: rawResponse.take(300)
                return Result.failure(Exception("OpenAI API: $errMsg"))
            }
            val text = json.getAsJsonArray("choices")
                .get(0).asJsonObject
                .getAsJsonObject("message")
                .get("content").asString
                .trim()
            Result.success(text)
        } catch (e: Exception) {
            Result.failure(Exception("OpenAI response parse error. Raw: ${rawResponse.take(300)}"))
        }
    }

    // ── Response Parser ───────────────────────

    private fun parseParams(paramsObj: com.google.gson.JsonObject?): Map<String, Any> {
        return paramsObj?.entrySet()?.associate { (k, v) ->
            k to when {
                v.isJsonPrimitive && v.asJsonPrimitive.isNumber -> v.asLong as Any
                else -> v.asString as Any
            }
        } ?: emptyMap()
    }

    private fun parseResponse(text: String): AgentPlan {
        val cleaned = text.trimIndent()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

        return try {
            val json = JsonParser.parseString(cleaned).asJsonObject
            when {
                // Multi-step: {"steps": [{...}, {...}]}
                json.has("steps") -> {
                    val stepsArray = json.getAsJsonArray("steps")
                    val steps = (0 until stepsArray.size()).map { i ->
                        val stepObj = stepsArray.get(i).asJsonObject
                        SkillStep(
                            skillId = stepObj.get("skill").asString,
                            params = parseParams(stepObj.getAsJsonObject("params")),
                        )
                    }
                    if (steps.size == 1) {
                        // Single step — treat as normal RUN_SKILL
                        AgentPlan(
                            type = PlanType.RUN_SKILL,
                            skillId = steps[0].skillId,
                            params = steps[0].params,
                        )
                    } else {
                        AgentPlan(type = PlanType.MULTI_STEP, steps = steps)
                    }
                }

                // Single skill: {"skill": "...", "params": {...}}
                json.has("skill") -> {
                    AgentPlan(
                        type = PlanType.RUN_SKILL,
                        skillId = json.get("skill").asString,
                        params = parseParams(json.getAsJsonObject("params")),
                    )
                }

                // Direct reply: {"reply": "..."}
                json.has("reply") -> AgentPlan(
                    type = PlanType.DIRECT_REPLY,
                    directReply = json.get("reply").asString,
                )

                else -> AgentPlan(PlanType.UNKNOWN, directReply = text)
            }
        } catch (e: Exception) {
            AgentPlan(PlanType.DIRECT_REPLY, directReply = text)
        }
    }

    fun clearHistory(chatId: Long) {
        history.remove(chatId)
    }
}
