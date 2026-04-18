package com.bharatdroid.agent

import com.google.gson.JsonParser

data class KnowledgeReply(
    val query: String,
    val topic: String,
    val summary: String,
    val reply: String,
    val sources: List<ResearchSource>,
)

enum class ResearchDepth(
    val resultLimit: Int,
    val pageFetchLimit: Int,
    val sourcePreviewCount: Int,
) {
    QUICK(resultLimit = 5, pageFetchLimit = 3, sourcePreviewCount = 3),
    DEEP(resultLimit = 8, pageFetchLimit = 5, sourcePreviewCount = 5),
}

class KnowledgeBrain(
    apiKey: String,
    provider: AIProvider,
    model: String = "",
    private val researcher: InternetResearcher = InternetResearcher(),
) {
    private val llm = LLMClient(
        apiKey = apiKey,
        provider = provider,
        model = model,
    )
    private val history = mutableMapOf<Long, ArrayDeque<AIChatMessage>>()

    suspend fun answer(
        chatId: Long,
        userMessage: String,
        contextHint: String = "",
        depth: ResearchDepth = ResearchDepth.QUICK,
    ): KnowledgeReply {
        val queryPlan = resolveQuery(chatId, userMessage, contextHint)
        if (queryPlan.query.isBlank() && queryPlan.reply.isNotBlank()) {
            rememberTurn(chatId, userMessage, queryPlan.reply)
            return KnowledgeReply(
                query = "",
                topic = queryPlan.topic,
                summary = queryPlan.reply,
                reply = queryPlan.reply.toTelegramSafeText(),
                sources = emptyList(),
            )
        }
        val query = queryPlan.query.ifBlank { userMessage.trim() }
        val topic = queryPlan.topic.ifBlank { query }
        val research = researcher.research(
            query = query,
            resultLimit = depth.resultLimit,
            pageFetchLimit = depth.pageFetchLimit,
        )

        if (research.sources.isEmpty()) {
            val fallback = "I could not find useful web results for that yet. Try rephrasing the person, company, or topic name."
            rememberTurn(chatId, userMessage, fallback)
            return KnowledgeReply(
                query = query,
                topic = topic,
                summary = fallback,
                reply = fallback,
                sources = emptyList(),
            )
        }

        val synthesized = synthesizeAnswer(
            userMessage = userMessage,
            contextHint = contextHint,
            query = query,
            topic = topic,
            research = research,
            depth = depth,
        )

        val summary = synthesized.summary.ifBlank { synthesized.answer }.take(800)
        val reply = buildReply(
            answer = synthesized.answer,
            sources = research.sources,
            depth = depth,
        )

        rememberTurn(chatId, userMessage, summary)

        return KnowledgeReply(
            query = query,
            topic = synthesized.topic.ifBlank { topic },
            summary = summary,
            reply = reply,
            sources = research.sources,
        )
    }

    fun clearHistory(chatId: Long) {
        history.remove(chatId)
    }

    private suspend fun resolveQuery(chatId: Long, userMessage: String, contextHint: String): QueryPlan {
        val priorTurns = history.getOrPut(chatId) { ArrayDeque() }.takeLast(6)
        val systemPrompt = """
You are BharatDroid's knowledge router.
Rewrite the user's latest message into the best web-search query.

Return only JSON:
{"query":"","topic":"","reply":""}

Rules:
1. Resolve pronouns like he, she, they, him, her, them, it, that guy, that company using the recent context.
2. "topic" should be the clean subject name when possible.
3. Keep "query" concise but specific enough for web search.
4. If the user is casually chatting and not asking for research, you may leave query blank and fill reply.
        """.trimIndent()

        val prompt = buildString {
            if (contextHint.isNotBlank()) {
                appendLine("Recent context:")
                appendLine(contextHint)
                appendLine()
            }
            if (priorTurns.isNotEmpty()) {
                appendLine("Recent knowledge turns:")
                priorTurns.forEach { turn ->
                    appendLine("${turn.role}: ${turn.content}")
                }
                appendLine()
            }
            appendLine("Latest user message:")
            append(userMessage.trim())
        }

        val raw = llm.generateJson(
            systemPrompt = systemPrompt,
            messages = listOf(AIChatMessage(role = "user", content = prompt)),
            temperature = 0.0,
        ).getOrNull().orEmpty()

        return try {
            val json = JsonParser.parseString(raw).asJsonObject
            QueryPlan(
                query = json.get("query")?.asString.orEmpty(),
                topic = json.get("topic")?.asString.orEmpty(),
                reply = json.get("reply")?.asString.orEmpty(),
            )
        } catch (_: Exception) {
            QueryPlan(query = userMessage.trim(), topic = "", reply = "")
        }
    }

    private suspend fun synthesizeAnswer(
        userMessage: String,
        contextHint: String,
        query: String,
        topic: String,
        research: ResearchPacket,
        depth: ResearchDepth,
    ): SynthesizedAnswer {
        val answerStyle = when (depth) {
            ResearchDepth.QUICK -> "Keep the answer concise and useful."
            ResearchDepth.DEEP -> "Give a deeper answer with the key points, but keep it readable and not bloated."
        }
        val systemPrompt = """
You are BharatDroid's knowledge brain.
Answer the user's question using ONLY the supplied web research.

Return only JSON:
{"answer":"","summary":"","topic":""}

Rules:
1. Do not invent facts that are not in the supplied research.
2. If sources disagree, mention the uncertainty briefly.
3. $answerStyle
4. "summary" should be a short memory-friendly version of the answer.
5. Use the same language as the user.
        """.trimIndent()

        val corpus = buildString {
            appendLine("User question: $userMessage")
            appendLine("Search query: $query")
            appendLine("Topic: $topic")
            appendLine("Research depth: ${depth.name.lowercase()}")
            if (contextHint.isNotBlank()) {
                appendLine("Recent context: $contextHint")
            }
            appendLine()
            appendLine("Web research:")
            research.sources.forEachIndexed { index, source ->
                appendLine("[${index + 1}] ${source.title}")
                appendLine("Domain: ${source.domain}")
                appendLine("Snippet: ${source.snippet}")
                if (source.extract.isNotBlank()) {
                    appendLine("Extract: ${source.extract}")
                }
                appendLine()
            }
        }

        val raw = llm.generateJson(
            systemPrompt = systemPrompt,
            messages = listOf(AIChatMessage(role = "user", content = corpus)),
            temperature = 0.1,
        ).getOrNull().orEmpty()

        return try {
            val json = JsonParser.parseString(raw).asJsonObject
            SynthesizedAnswer(
                answer = json.get("answer")?.asString.orEmpty().ifBlank {
                    buildFallbackAnswer(research)
                },
                summary = json.get("summary")?.asString.orEmpty(),
                topic = json.get("topic")?.asString.orEmpty(),
            )
        } catch (_: Exception) {
            SynthesizedAnswer(
                answer = buildFallbackAnswer(research),
                summary = buildFallbackAnswer(research).take(240),
                topic = topic,
            )
        }
    }

    private fun buildFallbackAnswer(research: ResearchPacket): String {
        val first = research.sources.firstOrNull()
            ?: return "I found web results, but I could not summarize them cleanly."
        return buildString {
            append(first.snippet.ifBlank { first.extract.take(240) }.ifBlank { "I found some web information, but the page summary was thin." })
            val second = research.sources.getOrNull(1)
            if (second != null && second.snippet.isNotBlank()) {
                append(" ")
                append(second.snippet.take(180))
            }
        }.trim()
    }

    private fun buildReply(answer: String, sources: List<ResearchSource>, depth: ResearchDepth): String {
        val sourceLines = sources.take(depth.sourcePreviewCount).mapIndexed { index, source ->
            "${index + 1}. ${source.title.take(70).toTelegramSafeText()} (${source.domain.ifBlank { source.url }.toTelegramSafeText()})"
        }.joinToString("\n")

        return buildString {
            append(answer.trim().toTelegramSafeText())
            if (sourceLines.isNotBlank()) {
                append("\n\nSources:\n")
                append(sourceLines)
            }
        }.trim().take(3500)
    }

    private fun rememberTurn(chatId: Long, userMessage: String, answerSummary: String) {
        val turns = history.getOrPut(chatId) { ArrayDeque() }
        turns.addLast(AIChatMessage(role = "user", content = userMessage.take(240)))
        turns.addLast(AIChatMessage(role = "assistant", content = answerSummary.take(400)))
        while (turns.size > 8) turns.removeFirst()
    }

    private data class QueryPlan(
        val query: String,
        val topic: String,
        val reply: String,
    )

    private data class SynthesizedAnswer(
        val answer: String,
        val summary: String,
        val topic: String,
    )

    private fun String.toTelegramSafeText(): String =
        replace("*", "")
            .replace("`", "")
            .replace("[", "")
            .replace("]", "")
            .replace("_", "")
}
