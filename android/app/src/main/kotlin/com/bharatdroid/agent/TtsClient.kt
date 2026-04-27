package com.bharatdroid.agent

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Text-to-Speech client.
 *
 * Production approach: render outgoing text to an MP3 file on disk that the
 * caller can ship as a Telegram voice/audio message. Two providers supported:
 *
 *  • OPENAI  — uses /v1/audio/speech (model: tts-1, voice: alloy by default).
 *              Returns MP3 bytes directly. Reliable and fast (~1-2s for short text).
 *  • OFF     — TTS disabled. synthesize() returns null.
 *
 * The TTS API key is OPTIONAL — if not configured, voice is simply skipped.
 * We deliberately keep the surface tiny so it's easy for the user to turn on
 * without thinking about formats or models.
 */
enum class TtsProvider { OFF, OPENAI }

class TtsClient(
    private val context: Context,
    private val provider: TtsProvider,
    private val apiKey: String,
    private val voice: String = "alloy",
) {
    private val client = OkHttpClient.Builder()
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    val isEnabled: Boolean
        get() = provider != TtsProvider.OFF && apiKey.isNotBlank()

    /**
     * Render [text] to an MP3 file in the cache directory. Returns null if TTS
     * is disabled or synthesis failed — callers should treat that as "skip voice
     * and just send text". Limits to 4000 chars to keep latency + cost sane.
     */
    suspend fun synthesize(text: String): File? = withContext(Dispatchers.IO) {
        if (!isEnabled) return@withContext null
        val clean = text
            .replace(Regex("\\*+"), "") // strip Markdown bold/italic
            .replace(Regex("`+"), "")
            .replace(Regex("_+"), "")
            .replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1") // [text](url) → text
            .replace(Regex("https?://\\S+"), "")
            .trim()
            .take(4000)
        if (clean.isBlank()) return@withContext null

        when (provider) {
            TtsProvider.OFF -> null
            TtsProvider.OPENAI -> renderOpenAi(clean)
        }
    }

    private fun renderOpenAi(text: String): File? {
        val body = gson.toJson(
            mapOf(
                "model" to "tts-1",
                "voice" to voice,
                "input" to text,
                "response_format" to "mp3",
            )
        )
        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/speech")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                if (bytes.isEmpty()) return null
                val outDir = File(context.cacheDir, "tts").apply { mkdirs() }
                val outFile = File(outDir, "tts_${System.currentTimeMillis()}.mp3")
                outFile.writeBytes(bytes)
                outFile
            }
        } catch (_: Exception) {
            null
        }
    }
}
