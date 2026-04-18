package com.bharatdroid.agent

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class ConversationContext(
    val lastKnowledgeQuery: String = "",
    val lastKnowledgeTopic: String = "",
    val lastKnowledgeSummary: String = "",
    val lastKnowledgeSources: List<String> = emptyList(),
    val lastActionRequest: String = "",
    val lastActionResult: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

class ConversationContextStore(context: Context) {
    private val prefs = context.getSharedPreferences("bharatdroid_conversation_context", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun get(chatId: Long): ConversationContext =
        loadAll()[chatId.toString()] ?: ConversationContext()

    fun rememberKnowledge(
        chatId: Long,
        query: String,
        topic: String,
        summary: String,
        sources: List<String>,
    ) {
        update(chatId) { current ->
            current.copy(
                lastKnowledgeQuery = query.take(200),
                lastKnowledgeTopic = topic.take(160),
                lastKnowledgeSummary = summary.take(800),
                lastKnowledgeSources = sources.take(5),
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    fun rememberAction(chatId: Long, request: String, result: String) {
        update(chatId) { current ->
            current.copy(
                lastActionRequest = request.take(200),
                lastActionResult = result.take(400),
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    fun clear(chatId: Long) {
        val all = loadAll().toMutableMap()
        all.remove(chatId.toString())
        saveAll(all)
    }

    fun buildRouterContext(chatId: Long): String {
        val context = get(chatId)
        if (context.lastKnowledgeTopic.isBlank() && context.lastActionRequest.isBlank()) return ""
        return buildString {
            if (context.lastKnowledgeTopic.isNotBlank()) {
                appendLine("Recent researched topic: ${context.lastKnowledgeTopic}")
                if (context.lastKnowledgeSummary.isNotBlank()) {
                    appendLine("Recent researched summary: ${context.lastKnowledgeSummary}")
                }
            }
            if (context.lastActionRequest.isNotBlank()) {
                appendLine("Recent action request: ${context.lastActionRequest}")
                appendLine("Recent action result: ${context.lastActionResult}")
            }
        }.trim()
    }

    fun buildActionContext(chatId: Long): String {
        val context = get(chatId)
        return buildString {
            if (context.lastKnowledgeTopic.isNotBlank()) {
                appendLine("Recent researched subject: ${context.lastKnowledgeTopic}")
            }
            if (context.lastKnowledgeSummary.isNotBlank()) {
                appendLine("Recent researched details: ${context.lastKnowledgeSummary}")
            }
            if (context.lastActionRequest.isNotBlank()) {
                appendLine("Last action request: ${context.lastActionRequest}")
            }
            if (context.lastActionResult.isNotBlank()) {
                appendLine("Last action result: ${context.lastActionResult}")
            }
            if (context.lastKnowledgeTopic.isNotBlank()) {
                appendLine("If the user says him, her, them, it, that person, or that company, it probably refers to the recent researched subject.")
            }
        }.trim()
    }

    fun buildKnowledgeContext(chatId: Long): String {
        val context = get(chatId)
        return buildString {
            if (context.lastKnowledgeTopic.isNotBlank()) {
                appendLine("Recent topic: ${context.lastKnowledgeTopic}")
            }
            if (context.lastKnowledgeSummary.isNotBlank()) {
                appendLine("Recent summary: ${context.lastKnowledgeSummary}")
            }
            if (context.lastActionResult.isNotBlank()) {
                appendLine("Recent action result: ${context.lastActionResult}")
            }
        }.trim()
    }

    private fun update(chatId: Long, transform: (ConversationContext) -> ConversationContext) {
        val all = loadAll().toMutableMap()
        val key = chatId.toString()
        all[key] = transform(all[key] ?: ConversationContext())
        saveAll(all)
    }

    private fun loadAll(): Map<String, ConversationContext> {
        val raw = prefs.getString("contexts", null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, ConversationContext>>() {}.type
            gson.fromJson<Map<String, ConversationContext>>(raw, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveAll(map: Map<String, ConversationContext>) {
        prefs.edit().putString("contexts", gson.toJson(map)).apply()
    }
}
