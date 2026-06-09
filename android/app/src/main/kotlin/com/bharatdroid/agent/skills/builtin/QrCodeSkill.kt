package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.Skill
import com.bharatdroid.agent.skills.SkillContext
import com.bharatdroid.agent.skills.SkillManifest
import com.bharatdroid.agent.skills.SkillResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLEncoder

/**
 * Generates a QR code for any text, URL, UPI ID, or contact info and sends it
 * directly in the Telegram chat. No API key needed - uses qrserver.com (free).
 *
 * Examples:
 *   "make a QR code for bharatclaw.com"
 *   "generate QR for UPI ID payments@yourbank"
 *   "QR code for my number 9876543210"
 */
class QrCodeSkill : Skill {

    override val manifest = SkillManifest(
        id = "qr_code",
        name = "QR Code Generator",
        version = "1.0.0",
        description = "Generate a QR code for any URL, text, UPI ID, phone number or contact and send it directly in Telegram chat - no app or API key needed.",
        author = "bharatclaw-team",
        trusted = true,
        permissions = emptySet(),
        exampleParamsHint = """{"content":"https://bharatclaw.com"} | {"content":"upi://pay?pa=you@upi","label":"My UPI QR"} | {"content":"9876543210","label":"Call me"}""",
    )

    private val client = SharedHttpClient.instance

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val content = (params["content"] as? String
            ?: params["text"] as? String
            ?: params["url"] as? String
            ?: params["data"] as? String
            ?: params["value"] as? String)
            ?.trim()
            ?: return SkillResult.Failure("What should the QR code contain? Give me a URL, text, UPI ID, or phone number.")

        val label = (params["label"] as? String)?.trim()
        val sizeParam = (params["size"] as? Number)?.toInt()?.coerceIn(100, 1000) ?: 400
        val caption = label ?: "QR: ${content.take(60)}${if (content.length > 60) "..." else ""}"

        return withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(content, "UTF-8")
                val url = "https://api.qrserver.com/v1/create-qr-code/?data=$encoded&size=${sizeParam}x${sizeParam}&format=png&margin=10"

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "BharatClaw-Agent/1.0")
                    .build()

                val bytes = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext SkillResult.Failure("QR code service unavailable (${response.code}). Try again.")
                    }
                    response.body?.bytes()
                        ?: return@withContext SkillResult.Failure("Empty response from QR service.")
                }

                SkillResult.Media(
                    bytes = bytes,
                    caption = "$caption",
                    mimeType = "image/png",
                    filename = "qrcode.png",
                )
            } catch (e: Exception) {
                SkillResult.Failure("QR code generation failed: ${e.message}")
            }
        }
    }
}
