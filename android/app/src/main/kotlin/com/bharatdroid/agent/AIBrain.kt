package com.bharatdroid.agent

import com.google.gson.JsonParser

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
            key.startsWith("AIza") -> AIProvider.GEMINI
            key.startsWith("sk-") -> AIProvider.OPENAI
            else -> AIProvider.GEMINI
        }

        fun detectFastestModel(key: String): String = when (detectProvider(key)) {
            AIProvider.GEMINI -> "gemini-2.5-flash"
            AIProvider.CLAUDE -> "claude-haiku-4-5-20251001"
            AIProvider.OPENAI -> "gpt-4o-mini"
        }
    }

    private val llm = LLMClient(
        apiKey = apiKey,
        provider = provider,
        model = model,
    )
    private val history = mutableMapOf<Long, ArrayDeque<AIChatMessage>>()

    private val systemPrompt: String
        get() {
            val skillsList = availableSkills.joinToString("\n") { skill ->
                "- ${skill.id}: ${skill.description}. Example params: ${skill.exampleParams}"
            }
            return """
You are BharatDroid, an AI agent running directly on an Android phone in India.
You control Android apps on the user's behalf via Telegram commands.

Available skills:
$skillsList

Rules:
1. SINGLE task -> return: {"skill": "<skill_id>", "params": {<key-value pairs>}}
2. MULTI-STEP task -> return: {"steps": [{"skill":"<id>","params":{...}}, {"skill":"<id>","params":{...}}]}
3. Just chatting / question -> return: {"reply": "<your answer>"}
4. Always reply in the same language the user used.
5. Be concise. Do not explain if a phone action is requested.
6. If no specific skill matches, use "general" skill with a "goal" param.
7. App opening requests like "open X", "launch X", "start X" should use the "general" skill unless X clearly belongs to a specific skill.
8. Use YouTube only for explicit YouTube/video/music intents.
9. WhatsApp send requests must include exact "contact" and "message".
10. Chrome should handle general web tasks with action="search" or action="goal".
11. Match the app intent precisely.
12. Gmail can read, compose, or search.
13. Calendar can show today/week or create events.
14. Notes can create, read, or search.
15. Contacts can search or call.
16. Files can browse, search, open, or show downloads.
17. Settings should target a section when possible.
18. Screen skill is for reading or finding on-screen content.
19. Reading screens, documents, or invoices means the "screen" skill.
20. Unknown apps should go through the "general" skill.
21. Instagram, Swiggy, Zomato, Maps and similar apps can use action="goal" for complex multi-step intents.
22. Multi-step requests should become ordered "steps".

Do NOT return anything outside JSON.
            """.trimIndent()
        }

    suspend fun process(chatId: Long, userMessage: String, contextHint: String = ""): AgentPlan {
        val chatHistory = history.getOrPut(chatId) { ArrayDeque() }
        while (chatHistory.size > 8) chatHistory.removeFirst()

        val effectiveMessage = buildString {
            if (contextHint.isNotBlank()) {
                appendLine("Context:")
                appendLine(contextHint.trim())
                appendLine()
            }
            append(userMessage)
        }.trim()

        chatHistory.addLast(AIChatMessage(role = "user", content = effectiveMessage))

        val result = llm.generateJson(
            systemPrompt = systemPrompt,
            messages = chatHistory.toList(),
            temperature = 0.0,
        )

        if (result.isFailure) {
            return AgentPlan(
                PlanType.DIRECT_REPLY,
                directReply = "AI error: ${result.exceptionOrNull()?.message ?: "Unknown error"}"
            )
        }

        val assistantText = result.getOrNull()
            ?: return AgentPlan(
                PlanType.DIRECT_REPLY,
                directReply = "AI returned empty response. Check your API key and try again."
            )

        chatHistory.addLast(AIChatMessage(role = "assistant", content = assistantText))
        while (chatHistory.size > 8) chatHistory.removeFirst()

        return parseResponse(assistantText)
    }

    private fun parseParams(paramsObj: com.google.gson.JsonObject?): Map<String, Any> {
        return paramsObj?.entrySet()?.associate { (key, value) ->
            key to when {
                value.isJsonPrimitive && value.asJsonPrimitive.isNumber -> {
                    try {
                        value.asLong as Any
                    } catch (_: Exception) {
                        value.asDouble as Any
                    }
                }

                value.isJsonPrimitive && value.asJsonPrimitive.isBoolean -> value.asBoolean as Any
                value.isJsonPrimitive -> value.asString as Any
                else -> try {
                    value.toString() as Any
                } catch (_: Exception) {
                    "" as Any
                }
            }
        } ?: emptyMap()
    }

    private fun parseResponse(text: String): AgentPlan {
        val cleaned = text.trimIndent()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        return try {
            val json = JsonParser.parseString(cleaned).asJsonObject
            when {
                json.has("steps") -> {
                    val stepsArray = json.getAsJsonArray("steps")
                    val steps = (0 until stepsArray.size()).map { index ->
                        val stepObj = stepsArray.get(index).asJsonObject
                        SkillStep(
                            skillId = stepObj.get("skill").asString,
                            params = parseParams(stepObj.getAsJsonObject("params")),
                        )
                    }
                    if (steps.size == 1) {
                        AgentPlan(
                            type = PlanType.RUN_SKILL,
                            skillId = steps.first().skillId,
                            params = steps.first().params,
                        )
                    } else {
                        AgentPlan(type = PlanType.MULTI_STEP, steps = steps)
                    }
                }

                json.has("skill") -> {
                    AgentPlan(
                        type = PlanType.RUN_SKILL,
                        skillId = json.get("skill").asString,
                        params = parseParams(json.getAsJsonObject("params")),
                    )
                }

                json.has("reply") -> AgentPlan(
                    type = PlanType.DIRECT_REPLY,
                    directReply = json.get("reply").asString,
                )

                else -> AgentPlan(PlanType.UNKNOWN, directReply = text)
            }
        } catch (_: Exception) {
            AgentPlan(PlanType.DIRECT_REPLY, directReply = text)
        }
    }

    fun clearHistory(chatId: Long) {
        history.remove(chatId)
    }
}
