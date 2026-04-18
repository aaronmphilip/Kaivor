package com.bharatdroid.agent

import com.google.gson.JsonParser

enum class BrainMode { ACTION, KNOWLEDGE, HYBRID, DIRECT_REPLY, UNKNOWN }

data class BrainRoute(
    val mode: BrainMode,
    val actionPrompt: String = "",
    val knowledgeQuery: String = "",
    val reply: String = "",
)

class BrainRouter(
    apiKey: String,
    provider: AIProvider,
    model: String = "",
) {
    private val llm = LLMClient(
        apiKey = apiKey,
        provider = provider,
        model = model,
    )

    suspend fun route(message: String, contextHint: String = ""): BrainRoute {
        val trimmed = message.trim()
        if (trimmed.isBlank()) return BrainRoute(mode = BrainMode.DIRECT_REPLY, reply = "Send me a message first.")

        val systemPrompt = """
You route Telegram messages for BharatDroid, a remote Android phone agent.

Return only JSON with this shape:
{"mode":"ACTION|KNOWLEDGE|HYBRID|DIRECT_REPLY","actionPrompt":"","knowledgeQuery":"","reply":""}

Routing rules:
1. ACTION = the user wants the phone to do something: open apps, send messages, order, book, play, read phone data, check notifications, use WhatsApp, Telegram, Chrome, Gmail, Calendar, etc.
2. KNOWLEDGE = the user wants factual information from the open web about a person, company, place, event, product, or current topic.
3. HYBRID = the user wants both: research first, then perform a phone action using that research or the researched person/company.
4. DIRECT_REPLY = only for tiny conversational replies about the assistant itself when no phone action or web research is needed.
5. If the user asks about their own phone state or apps, that is ACTION, not KNOWLEDGE.
6. Resolve pronouns using the recent context when possible.
7. For ACTION, rewrite the task into a clean action prompt if needed.
8. For KNOWLEDGE, produce a concise web-search query.
9. For HYBRID, fill both actionPrompt and knowledgeQuery.
10. Never leave mode empty.
        """.trimIndent()

        val userPrompt = buildString {
            if (contextHint.isNotBlank()) {
                appendLine("Recent context:")
                appendLine(contextHint)
                appendLine()
            }
            appendLine("Latest user message:")
            append(trimmed)
        }

        val result = llm.generateJson(
            systemPrompt = systemPrompt,
            messages = listOf(AIChatMessage(role = "user", content = userPrompt)),
            temperature = 0.0,
        )

        val raw = result.getOrNull()?.trim().orEmpty()
        if (result.isFailure || raw.isBlank()) {
            return fallback(trimmed)
        }

        return try {
            val json = JsonParser.parseString(raw).asJsonObject
            val mode = try {
                BrainMode.valueOf(json.get("mode")?.asString?.uppercase() ?: "UNKNOWN")
            } catch (_: Exception) {
                BrainMode.UNKNOWN
            }
            BrainRoute(
                mode = mode,
                actionPrompt = json.get("actionPrompt")?.asString.orEmpty(),
                knowledgeQuery = json.get("knowledgeQuery")?.asString.orEmpty(),
                reply = json.get("reply")?.asString.orEmpty(),
            ).takeUnless { it.mode == BrainMode.UNKNOWN } ?: fallback(trimmed)
        } catch (_: Exception) {
            fallback(trimmed)
        }
    }

    private fun fallback(message: String): BrainRoute {
        val lower = message.lowercase()
        val knowledgeSignals = listOf(
            "who is", "what is", "tell me about", "look up", "find out", "research",
            "background on", "latest info", "latest news", "information about", "where is",
            "when did", "why did", "how old is", "net worth", "career", "biography"
        )
        val actionSignals = listOf(
            "open", "launch", "start", "send", "message", "reply", "order", "book",
            "play", "call", "navigate", "read my", "check my", "show my", "create",
            "write", "search on", "whatsapp", "telegram", "gmail", "calendar",
            "youtube", "swiggy", "zomato", "zepto", "blinkit", "maps", "uber", "ola"
        )

        val looksKnowledge = knowledgeSignals.any { lower.contains(it) }
        val looksAction = actionSignals.any { lower.contains(it) }

        return when {
            looksKnowledge && looksAction -> BrainRoute(
                mode = BrainMode.HYBRID,
                actionPrompt = message,
                knowledgeQuery = message,
            )

            looksKnowledge -> BrainRoute(
                mode = BrainMode.KNOWLEDGE,
                knowledgeQuery = message,
            )

            else -> BrainRoute(
                mode = BrainMode.ACTION,
                actionPrompt = message,
            )
        }
    }
}
