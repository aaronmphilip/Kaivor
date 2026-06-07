package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.Skill
import com.bharatdroid.agent.skills.SkillContext
import com.bharatdroid.agent.skills.SkillManifest
import com.bharatdroid.agent.skills.SkillResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Generates an image from a text prompt and delivers it directly in Telegram chat.
 *
 * Supports:
 *   - Together AI  (provider = "together") — Flux.1-schnell, fast and cheap
 *   - OpenAI       (provider = "openai")   — DALL-E 3, high quality
 *
 * The user sets their image API key via Settings → Image API Key.
 * Provider is auto-detected from the key format, or can be set explicitly.
 */
class ImageGeneratorSkill(
    private val apiKey: String,
    private val provider: String = "together",
) : Skill {

    override val manifest = SkillManifest(
        id = "image_generate",
        name = "Image Generator",
        version = "1.0.0",
        description = "Generate any image from a text description and send it directly to your Telegram chat — no app needed. Powered by Flux/DALL-E AI.",
        author = "bharatclaw-team",
        trusted = true,
        permissions = emptySet(),
        exampleParamsHint = """{"prompt":"A majestic tiger in a jungle at sunset, photorealistic"} | {"prompt":"Eiffel Tower cartoon style","size":"square"}""",
    )

    private val httpClient = SharedHttpClient.imageInstance

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val prompt = (params["prompt"] as? String
            ?: params["description"] as? String
            ?: params["image"] as? String
            ?: params["text"] as? String)
            ?.trim()
            ?: return SkillResult.Failure("What should I generate? Give me a description, e.g. 'a golden temple at dawn'.")

        val sizeHint = (params["size"] as? String)?.lowercase()?.trim() ?: "square"
        val style = (params["style"] as? String)?.trim()
        val fullPrompt = if (style != null) "$prompt, $style style" else prompt

        return withContext(Dispatchers.IO) {
            try {
                val imageUrl = when {
                    provider.contains("openai") || apiKey.startsWith("sk-") -> generateWithOpenAI(fullPrompt, sizeHint)
                    else -> generateWithTogether(fullPrompt, sizeHint)
                } ?: return@withContext SkillResult.Failure("Image generation failed — check your API key and try again.")

                // Download the generated image
                val imageBytes = downloadBytes(imageUrl)
                    ?: return@withContext SkillResult.Failure("Generated image URL expired before I could download it. Try again.")

                SkillResult.Media(
                    bytes = imageBytes,
                    caption = "🎨 $prompt",
                    mimeType = "image/jpeg",
                    filename = "generated.jpg",
                )
            } catch (e: Exception) {
                SkillResult.Failure("Image generation error: ${e.message}")
            }
        }
    }

    private fun generateWithTogether(prompt: String, sizeHint: String): String? {
        val (width, height) = sizeForHint(sizeHint)
        val payload = JSONObject().apply {
            put("model", "black-forest-labs/FLUX.1-schnell-Free")
            put("prompt", prompt)
            put("n", 1)
            put("width", width)
            put("height", height)
            put("response_format", "url")
        }
        val request = Request.Builder()
            .url("https://api.together.xyz/v1/images/generations")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val body = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string().orEmpty()
                throw Exception("Together AI error ${response.code}: $err")
            }
            response.body?.string() ?: throw Exception("Empty response from Together AI.")
        }

        return JSONObject(body)
            .getJSONArray("data")
            .getJSONObject(0)
            .getString("url")
    }

    private fun generateWithOpenAI(prompt: String, sizeHint: String): String? {
        val size = when {
            sizeHint.contains("portrait") || sizeHint.contains("tall") -> "1024x1792"
            sizeHint.contains("landscape") || sizeHint.contains("wide") -> "1792x1024"
            else -> "1024x1024"
        }
        val payload = JSONObject().apply {
            put("model", "dall-e-3")
            put("prompt", prompt)
            put("n", 1)
            put("size", size)
            put("response_format", "url")
            put("quality", "standard")
        }
        val request = Request.Builder()
            .url("https://api.openai.com/v1/images/generations")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val body = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string().orEmpty()
                throw Exception("OpenAI error ${response.code}: $err")
            }
            response.body?.string() ?: throw Exception("Empty response from OpenAI.")
        }

        return JSONObject(body)
            .getJSONArray("data")
            .getJSONObject(0)
            .getString("url")
    }

    private fun downloadBytes(url: String): ByteArray? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "BharatClaw-Agent/1.0")
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null
            else response.body?.bytes()
        }
    }

    private fun sizeForHint(hint: String): Pair<Int, Int> = when {
        hint.contains("portrait") || hint.contains("tall") -> Pair(768, 1024)
        hint.contains("landscape") || hint.contains("wide") -> Pair(1024, 768)
        else -> Pair(1024, 1024)
    }
}
