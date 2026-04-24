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
9. WhatsApp text send requests must include exact "contact" and "message".
10. Chrome should handle general web tasks with action="search" or action="goal".
11. Match the app intent precisely.
12. Gmail can read, compose, or search.
13. Calendar can show today/week or create events.
14. Notes can create, read, or search.
15. Contacts can search or call.
16. Files can browse, search, open, or show downloads.
17. Settings should target a section when possible.
18. Screen skill is for reading or finding on-screen content already visible.
19. If the user wants a PDF, document, invoice, or article opened, scrolled, understood, and summarized from WhatsApp, Chrome, or another app, prefer the "reading_concierge" skill.
20. Unknown apps should go through the "general" skill.
21. Instagram, Swiggy, Zomato, Maps and similar apps can use action="goal" for complex multi-step intents.
22. Multi-step requests should become ordered "steps".
23. Ride tasks that compare Uber/Ola/Rapido, honor an explicit pickup, book a ride, tell the ETA, and message someone should prefer the "ride_concierge" skill.
24. If the user specifies a pickup location, pass it as "pickup" and do not replace it with current location.
25. If the user specifies "via Uber", "via Ola", or "via Rapido", pass it as "via". If the user specifies cab/auto/bike, pass it as "transport".
25a. Rapido is a bike-taxi and auto app (package com.rapido.passenger). Use the "rapido" skill for direct Rapido requests, or pass via="rapido" to ride_concierge.
26. For WhatsApp PDFs/documents use action="whatsapp_pdf" unless the request clearly says article/link.
27. For WhatsApp article links use action="whatsapp_article". For browser article reads use action="article".
28. If a reading/summarizing request mentions a contact, file, article topic, or desired output length like one paragraph/one page/two pages/detailed, pass those as "contact", "query", and "instruction" for "reading_concierge".
29. If the user wants to send or share a PDF, document, invoice, resume, or other file through WhatsApp, use the "whatsapp" skill with action="send_file". Pass the recipient in "contact", the file name or topic in "file" (or "query"), any folder hint in "folder", and any covering text in "caption".
30. If the user is following up on a recent food or shopping shortlist with phrases like "first one", "second one", "that one", "this one", "go ahead", or "place order", reuse the same app skill from context and pass action="continue" with the follow-up in "query".
31. For Zomato and Swiggy food requests, the first pass should usually use action="search" so the skill can search, compare options, and ask which one to order before adding anything.
32. When the user gives a budget like under Rs X, below X, or within X, pass maxPrice as a number.
33. For Calendar create/add/new event requests, include action="create" and extract title, date, time, and description whenever the user mentions them.
34. Preserve explicit date/time phrases such as "18th April", "tomorrow", weekdays, "4 pm", "4:30 pm", "430pm", "noon", or "midnight" instead of dropping them.
35. If a ride pickup should use GPS/current location/my location/right now location, set pickup to "current location" rather than inventing or guessing an address.
36. For YouTube: if the user says "full video", "long video", or just "full" after a previous YouTube query, route to youtube with action="play" and the original song/query as the value. If they say "shorts" or "short", append "shorts" to the query.
37. For WhatsApp multi-file requests (send X and Y to Z), use action="send_files" and pass a comma-separated "files" param, e.g. "files": "invoice.pdf,resume.pdf".
38. For WhatsApp requests, if the user describes a file by topic (e.g. "the latest invoice", "my resume"), pass that description as the "file" param and let the skill search for it.

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
