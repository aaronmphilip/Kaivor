package com.bharatdroid.agent

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class AIChatMessage(
    val role: String,
    val content: String,
)

class LLMClient(
    private val apiKey: String,
    private val provider: AIProvider = AIBrain.detectProvider(apiKey),
    private val model: String = "",
) {
    private val client = OkHttpClient()
    private val gson = Gson()

    suspend fun generateJson(
        systemPrompt: String,
        messages: List<AIChatMessage>,
        temperature: Double = 0.0,
    ): Result<String?> = when (provider) {
        AIProvider.CLAUDE -> callClaude(systemPrompt, messages, temperature)
        AIProvider.GEMINI -> callGemini(systemPrompt, messages, temperature, expectJson = true)
        AIProvider.OPENAI -> callOpenAI(systemPrompt, messages, temperature, expectJson = true)
    }

    suspend fun generateText(
        systemPrompt: String,
        messages: List<AIChatMessage>,
        temperature: Double = 0.2,
    ): Result<String?> = when (provider) {
        AIProvider.CLAUDE -> callClaude(systemPrompt, messages, temperature)
        AIProvider.GEMINI -> callGemini(systemPrompt, messages, temperature, expectJson = false)
        AIProvider.OPENAI -> callOpenAI(systemPrompt, messages, temperature, expectJson = false)
    }

    private fun resolvedModel(): String =
        model.ifBlank { AIBrain.detectFastestModel(apiKey) }

    private suspend fun callClaude(
        systemPrompt: String,
        messages: List<AIChatMessage>,
        temperature: Double,
    ): Result<String?> {
        val requestBody = gson.toJson(
            mapOf(
                "model" to resolvedModel(),
                "max_tokens" to 2048,
                "temperature" to temperature,
                "system" to systemPrompt,
                "messages" to messages.map {
                    mapOf(
                        "role" to it.role,
                        "content" to it.content,
                    )
                },
            )
        )

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
                .firstOrNull()
                ?.asJsonObject
                ?.get("text")
                ?.asString
                ?.trim()
            Result.success(text)
        } catch (e: Exception) {
            Result.failure(Exception("Claude response parse error. Raw: ${rawResponse.take(300)}"))
        }
    }

    private suspend fun callGemini(
        systemPrompt: String,
        messages: List<AIChatMessage>,
        temperature: Double,
        expectJson: Boolean,
    ): Result<String?> {
        val payload = linkedMapOf<String, Any>(
            "systemInstruction" to mapOf(
                "parts" to listOf(mapOf("text" to systemPrompt))
            ),
            "contents" to messages.map { msg ->
                mapOf(
                    "role" to if (msg.role == "assistant") "model" else "user",
                    "parts" to listOf(mapOf("text" to msg.content)),
                )
            },
            "generationConfig" to buildMap<String, Any> {
                put("temperature", temperature)
                put("thinkingConfig", mapOf("thinkingBudget" to 0))
                if (expectJson) put("responseMimeType", "application/json")
            },
        )

        val rawResponse = try {
            withContext(Dispatchers.IO) {
                client.newCall(
                    Request.Builder()
                        .url("https://generativelanguage.googleapis.com/v1beta/models/${resolvedModel()}:generateContent?key=$apiKey")
                        .addHeader("content-type", "application/json")
                        .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
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
                .firstOrNull()
                ?.asJsonObject
                ?.getAsJsonObject("content")
                ?.getAsJsonArray("parts")
                ?.firstOrNull()
                ?.asJsonObject
                ?.get("text")
                ?.asString
                ?.trim()
            Result.success(text)
        } catch (e: Exception) {
            Result.failure(Exception("Gemini response parse error. Raw: ${rawResponse.take(300)}"))
        }
    }

    private suspend fun callOpenAI(
        systemPrompt: String,
        messages: List<AIChatMessage>,
        temperature: Double,
        expectJson: Boolean,
    ): Result<String?> {
        val requestPayload = linkedMapOf<String, Any>(
            "model" to resolvedModel(),
            "temperature" to temperature,
            "messages" to buildList {
                add(mapOf("role" to "system", "content" to systemPrompt))
                messages.forEach { add(mapOf("role" to it.role, "content" to it.content)) }
            },
        )
        if (expectJson) {
            requestPayload["response_format"] = mapOf("type" to "json_object")
        }

        val rawResponse = try {
            withContext(Dispatchers.IO) {
                client.newCall(
                    Request.Builder()
                        .url("https://api.openai.com/v1/chat/completions")
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("content-type", "application/json")
                        .post(gson.toJson(requestPayload).toRequestBody("application/json".toMediaType()))
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
                .firstOrNull()
                ?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")
                ?.asString
                ?.trim()
            Result.success(text)
        } catch (e: Exception) {
            Result.failure(Exception("OpenAI response parse error. Raw: ${rawResponse.take(300)}"))
        }
    }
}
