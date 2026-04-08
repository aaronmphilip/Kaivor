package com.bharatdroid.agent

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

// ─────────────────────────────────────────────
// AI BRAIN
//
// Calls Claude API to turn natural language commands
// into structured skill invocations.
// Maintains per-chat conversation history.
// ─────────────────────────────────────────────

data class AgentPlan(
    val type: PlanType,
    val skillId: String? = null,
    val params: Map<String, Any> = emptyMap(),
    val directReply: String? = null,
)

enum class PlanType { RUN_SKILL, DIRECT_REPLY, UNKNOWN }

class AIBrain(
    private val claudeApiKey: String,
    private val availableSkills: List<SkillInfo>,
) {
    data class SkillInfo(val id: String, val description: String, val exampleParams: String)

    private val client = OkHttpClient()
    private val gson = Gson()

    // Per-chat message history (last 20 turns each)
    private val history = mutableMapOf<Long, ArrayDeque<Map<String, String>>>()

    private val systemPrompt: String get() {
        val skillsList = availableSkills.joinToString("\n") { s ->
            "- ${s.id}: ${s.description}. Example params: ${s.exampleParams}"
        }
        return """
You are BharatDroid — an AI agent running directly on an Android phone in India.
You control Android apps on the user's behalf via Telegram commands.

Available skills:
$skillsList

Rules:
1. When the user asks you to DO something on their phone, return ONLY valid JSON:
   {"skill": "<skill_id>", "params": {<key-value pairs>}}

2. When the user is just asking a question or chatting, return ONLY valid JSON:
   {"reply": "<your answer>"}

3. Always reply in the same language the user used (Hindi or English).
4. For anything involving money or purchases, always include the price in your reply before acting.
5. Be concise. Do not explain what you are about to do — just do it.
6. If a skill is not available for what the user wants, say so clearly in {"reply": "..."}.

Do NOT return anything outside of JSON. No markdown, no prose.
        """.trimIndent()
    }

    suspend fun process(chatId: Long, userMessage: String): AgentPlan {
        val chatHistory = history.getOrPut(chatId) { ArrayDeque() }
        chatHistory.addLast(mapOf("role" to "user", "content" to userMessage))

        val messages = chatHistory.map { gson.toJson(it) }
        val messagesJson = "[${messages.joinToString(",")}]"

        val requestBody = """
            {
                "model": "claude-haiku-4-5-20251001",
                "max_tokens": 512,
                "system": ${gson.toJson(systemPrompt)},
                "messages": $messagesJson
            }
        """.trimIndent()

        val rawResponse = withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder()
                    .url("https://api.anthropic.com/v1/messages")
                    .addHeader("x-api-key", claudeApiKey)
                    .addHeader("anthropic-version", "2023-06-01")
                    .addHeader("content-type", "application/json")
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().use { it.body?.string() ?: "" }
        }

        val assistantText = try {
            JsonParser.parseString(rawResponse)
                .asJsonObject
                .getAsJsonArray("content")
                .get(0).asJsonObject
                .get("text").asString
                .trim()
        } catch (e: Exception) {
            return AgentPlan(PlanType.DIRECT_REPLY, directReply = "Sorry, I had trouble thinking. Try again.")
        }

        chatHistory.addLast(mapOf("role" to "assistant", "content" to assistantText))
        // Keep history bounded
        while (chatHistory.size > 20) chatHistory.removeFirst()

        return parseResponse(assistantText)
    }

    private fun parseResponse(text: String): AgentPlan {
        // Strip markdown code blocks if Claude wraps the JSON anyway
        val cleaned = text.trimIndent()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

        return try {
            val json = JsonParser.parseString(cleaned).asJsonObject
            when {
                json.has("skill") -> {
                    val params = json.getAsJsonObject("params")?.entrySet()
                        ?.associate { (k, v) ->
                            k to when {
                                v.isJsonPrimitive && v.asJsonPrimitive.isNumber -> v.asLong as Any
                                else -> v.asString as Any
                            }
                        } ?: emptyMap()

                    AgentPlan(
                        type = PlanType.RUN_SKILL,
                        skillId = json.get("skill").asString,
                        params = params,
                    )
                }
                json.has("reply") -> AgentPlan(
                    type = PlanType.DIRECT_REPLY,
                    directReply = json.get("reply").asString,
                )
                else -> AgentPlan(PlanType.UNKNOWN, directReply = text)
            }
        } catch (e: Exception) {
            // If Claude didn't follow instructions and just replied in prose, show it
            AgentPlan(PlanType.DIRECT_REPLY, directReply = text)
        }
    }

    fun clearHistory(chatId: Long) {
        history.remove(chatId)
    }
}
