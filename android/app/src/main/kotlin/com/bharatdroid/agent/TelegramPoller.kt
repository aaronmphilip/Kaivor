package com.bharatdroid.agent

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

// ─────────────────────────────────────────────
// TELEGRAM POLLER
//
// The Android phone IS the bot. No server needed.
// This polls Telegram's getUpdates long-poll endpoint
// every ~1s, processes messages, and replies.
// ─────────────────────────────────────────────

data class IncomingMessage(
    val updateId: Long,
    val chatId: Long,
    val username: String?,
    val text: String,
)

class TelegramPoller(
    private val botToken: String,
    private val authorizedChatIds: Set<Long>,
    private val onMessage: suspend (msg: IncomingMessage) -> String,
) {
    private val client = OkHttpClient.Builder()
        .callTimeout(35, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val baseUrl = "https://api.telegram.org/bot$botToken"
    private var lastUpdateId = 0L
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        scope.launch {
            while (isActive) {
                try {
                    val updates = fetchUpdates()
                    for (msg in updates) {
                        lastUpdateId = maxOf(lastUpdateId, msg.updateId)

                        if (msg.chatId !in authorizedChatIds) {
                            sendMessage(msg.chatId, "This is a private BharatDroid agent. Unauthorized.")
                            continue
                        }

                        // Handle each message in parallel so one slow skill doesn't block others
                        scope.launch {
                            try {
                                val reply = onMessage(msg)
                                sendMessage(msg.chatId, reply)
                            } catch (e: Exception) {
                                sendMessage(msg.chatId, "Error: ${e.message}")
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    delay(5_000) // Back off on network error
                }
            }
        }
    }

    suspend fun sendMessage(chatId: Long, text: String, parseMode: String = "Markdown") {
        val body = gson.toJson(mapOf(
            "chat_id" to chatId,
            "text" to text,
            "parse_mode" to parseMode,
        ))
        val request = Request.Builder()
            .url("$baseUrl/sendMessage")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        withContext(Dispatchers.IO) {
            try { client.newCall(request).execute().close() }
            catch (e: IOException) { /* best-effort send */ }
        }
    }

    suspend fun sendTyping(chatId: Long) {
        val body = gson.toJson(mapOf("chat_id" to chatId, "action" to "typing"))
        val request = Request.Builder()
            .url("$baseUrl/sendChatAction")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        withContext(Dispatchers.IO) {
            try { client.newCall(request).execute().close() }
            catch (e: IOException) { /* ignore */ }
        }
    }

    fun stop() = scope.cancel()

    // ── Internal ──────────────────────────────

    private suspend fun fetchUpdates(): List<IncomingMessage> {
        // Long-poll: Telegram holds the connection for up to 30s if no updates
        val url = "$baseUrl/getUpdates?offset=${lastUpdateId + 1}&timeout=30&allowed_updates=[\"message\"]"
        val request = Request.Builder().url(url).build()

        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: return@use emptyList()
                val json = gson.fromJson(bodyStr, JsonObject::class.java)
                if (!json.get("ok").asBoolean) return@use emptyList()

                json.getAsJsonArray("result").mapNotNull { element ->
                    val update = element.asJsonObject
                    val message = update.getAsJsonObject("message") ?: return@mapNotNull null
                    val chat = message.getAsJsonObject("chat")
                    val text = message.get("text")?.asString ?: return@mapNotNull null
                    val from = message.getAsJsonObject("from")

                    IncomingMessage(
                        updateId = update.get("update_id").asLong,
                        chatId = chat.get("id").asLong,
                        username = from?.get("username")?.asString,
                        text = text,
                    )
                }
            }
        }
    }
}
