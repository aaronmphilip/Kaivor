package com.bharatdroid.agent

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request

// ─────────────────────────────────────────────
// CHAT ID DETECTOR
//
// Given a bot token, starts polling for the FIRST
// /start message. When it arrives, returns the
// chat ID. This eliminates the "@userinfobot" step —
// user just sends /start to their bot and we catch it.
// ─────────────────────────────────────────────

class ChatIdDetector(private val botToken: String) {

    private val client = OkHttpClient.Builder()
        .callTimeout(35, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val baseUrl = "https://api.telegram.org/bot$botToken"

    data class DetectedUser(
        val chatId: Long,
        val firstName: String,
        val username: String?,
    )

    // Blocks until a /start message is received, then returns the user info.
    // Call from a coroutine — it polls every 2s.
    suspend fun waitForStart(timeout: Long = 120_000): DetectedUser? = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeout
        var offset = 0L

        // First, clear any stale updates
        try {
            val clearUrl = "$baseUrl/getUpdates?offset=-1&limit=1"
            client.newCall(Request.Builder().url(clearUrl).build()).execute().use { resp ->
                val body = resp.body?.string() ?: return@use
                val json = gson.fromJson(body, JsonObject::class.java)
                val results = json.getAsJsonArray("result")
                if (results != null && results.size() > 0) {
                    offset = results.last().asJsonObject.get("update_id").asLong + 1
                }
            }
        } catch (_: Exception) {}

        while (System.currentTimeMillis() < deadline) {
            try {
                val url = "$baseUrl/getUpdates?offset=$offset&timeout=5&allowed_updates=[\"message\"]"
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute().use { resp ->
                    resp.body?.string()
                } ?: continue

                val json = gson.fromJson(response, JsonObject::class.java)
                if (!json.get("ok").asBoolean) { delay(2000); continue }

                val results = json.getAsJsonArray("result")
                for (element in results) {
                    val update = element.asJsonObject
                    offset = update.get("update_id").asLong + 1
                    val message = update.getAsJsonObject("message") ?: continue
                    val text = message.get("text")?.asString ?: continue

                    if (text.trim().startsWith("/start")) {
                        val chat = message.getAsJsonObject("chat")
                        val from = message.getAsJsonObject("from")

                        // Send a confirmation back
                        sendWelcome(chat.get("id").asLong, from?.get("first_name")?.asString ?: "there")

                        return@withContext DetectedUser(
                            chatId = chat.get("id").asLong,
                            firstName = from?.get("first_name")?.asString ?: "",
                            username = from?.get("username")?.asString,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                delay(2000)
            }
        }
        null // Timed out
    }

    data class BotInfo(val displayName: String, val username: String)

    // Validate that a bot token is real by calling getMe
    suspend fun validateToken(): String? = withContext(Dispatchers.IO) {
        try {
            val info = getBotInfo() ?: return@withContext null
            info.displayName
        } catch (_: Exception) { null }
    }

    // Get full bot info including username
    suspend fun getBotInfo(): BotInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/getMe").build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string() ?: return@withContext null
                val json = gson.fromJson(body, JsonObject::class.java)
                if (!json.get("ok").asBoolean) return@withContext null
                val result = json.getAsJsonObject("result")
                val displayName = result?.get("first_name")?.asString ?: return@withContext null
                val username = result.get("username")?.asString ?: return@withContext null
                BotInfo(displayName, username)
            }
        } catch (_: Exception) { null }
    }

    private suspend fun sendWelcome(chatId: Long, name: String) {
        try {
            val msg = "Hey $name! BharatDroid is pairing with your account... ✓"
            val url = "$baseUrl/sendMessage?chat_id=$chatId&text=${java.net.URLEncoder.encode(msg, "UTF-8")}"
            withContext(Dispatchers.IO) {
                client.newCall(Request.Builder().url(url).build()).execute().close()
            }
        } catch (_: Exception) {}
    }
}
