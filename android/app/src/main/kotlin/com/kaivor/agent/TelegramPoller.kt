package com.kaivor.agent

import android.graphics.Bitmap
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
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
    val attachment: TelegramAttachment? = null,
    /** If the user replied to a previous bot message, this is that message's Telegram id. */
    val replyToMessageId: Long? = null,
)

enum class TelegramAttachmentKind { DOCUMENT, PHOTO, AUDIO }

data class TelegramAttachment(
    val kind: TelegramAttachmentKind,
    val fileId: String,
    val uniqueId: String = "",
    val fileName: String = "",
    val mimeType: String = "",
    val sizeBytes: Long = 0L,
)

data class DownloadedTelegramAttachment(
    val attachment: TelegramAttachment,
    val file: File,
    val displayName: String,
    val mimeType: String,
)

data class TelegramBotCommand(
    val command: String,
    val description: String,
)

class TelegramPoller(
    private val botToken: String,
    private val authorizedChatIds: Set<Long>,
    private val downloadDir: File,
    private val commands: List<TelegramBotCommand> = emptyList(),
    private val onMessage: suspend (msg: IncomingMessage) -> String,
    /** Persists lastUpdateId so commands don't replay after service restart / reboot. */
    private val saveOffset: (Long) -> Unit = {},
    initialOffset: Long = 0L,
) {
    private val client = OkHttpClient.Builder()
        .callTimeout(35, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val baseUrl = "https://api.telegram.org/bot$botToken"
    // Restored from SharedPreferences — prevents command replay after restart/reboot.
    private var lastUpdateId = initialOffset
    private var commandsSynced = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        downloadDir.mkdirs()
    }

    fun start() {
        scope.launch {
            while (isActive) {
                try {
                    if (!commandsSynced) {
                        commandsSynced = syncCommandMenu()
                    }
                    val updates = fetchUpdates()
                    for (msg in updates) {
                        val newId = maxOf(lastUpdateId, msg.updateId)
                        if (newId != lastUpdateId) { lastUpdateId = newId; saveOffset(newId) }

                        if (msg.chatId !in authorizedChatIds) {
                            sendMessage(msg.chatId, "This is a private Kaivor agent. Unauthorized.")
                            continue
                        }

                        // Handle each message in parallel so one slow skill doesn't block others
                        scope.launch {
                            try {
                                val reply = onMessage(msg)
                                // Empty reply means the handler already sent the response (e.g. as a photo)
                                if (reply.isNotBlank()) sendMessage(msg.chatId, reply)
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

    /**
     * Sends a Telegram message and returns the Telegram `message_id` of the outgoing message.
     * Used by the notification relay to map forwarded notifications → incoming Telegram replies
     * (so a user's reply in Telegram can be routed back to the source app).
     * Returns null if the send failed.
     */
    suspend fun sendMessage(chatId: Long, text: String, parseMode: String? = "Markdown"): Long? {
        if (text.isBlank()) return null

        val payload = linkedMapOf<String, Any>(
            "chat_id" to chatId,
            "text" to text.take(4096),
        )
        if (!parseMode.isNullOrBlank()) payload["parse_mode"] = parseMode
        buildCommandKeyboard()?.let { payload["reply_markup"] = it }

        val body = gson.toJson(payload)
        val request = Request.Builder()
            .url("$baseUrl/sendMessage")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string() ?: return@use null
                    val json = gson.fromJson(bodyStr, JsonObject::class.java)
                    if (!json.get("ok").asBoolean) return@use null
                    json.getAsJsonObject("result")?.get("message_id")?.asLong
                }
            } catch (_: IOException) { null }
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

    // Send a screenshot as a photo with caption — the "proof" feature
    suspend fun sendPhoto(chatId: Long, bitmap: Bitmap, caption: String = "") {
        try {
            // Hardware bitmaps can't be compressed directly — copy to software first
            val soft = if (bitmap.config == Bitmap.Config.HARDWARE)
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            else bitmap

            val out = ByteArrayOutputStream()
            soft.compress(Bitmap.CompressFormat.JPEG, 85, out)
            val bytes = out.toByteArray()

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId.toString())
                .addFormDataPart("caption", caption.take(1024))
                .addFormDataPart("photo", "result.jpg",
                    bytes.toRequestBody("image/jpeg".toMediaType()))
                .apply {
                    buildCommandKeyboard()?.let { replyMarkup ->
                        addFormDataPart("reply_markup", gson.toJson(replyMarkup))
                    }
                }
                .build()

            val request = Request.Builder()
                .url("$baseUrl/sendPhoto")
                .post(body)
                .build()

            withContext(Dispatchers.IO) {
                try { client.newCall(request).execute().close() }
                catch (_: IOException) {
                    // Photo failed — fall back to text
                    sendMessage(chatId, caption)
                }
            }
        } catch (_: Exception) {
            sendMessage(chatId, caption)
        }
    }

    /**
     * Send an audio file as a Telegram voice message — appears with the inline playback
     * bar like a recorded note. Accepts MP3 (Telegram supports it directly for voice).
     * The file is left untouched on disk so the caller can clean it up.
     */
    suspend fun sendVoice(chatId: Long, audioFile: File, caption: String = "") {
        if (!audioFile.exists() || audioFile.length() == 0L) return
        try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId.toString())
                .apply { if (caption.isNotBlank()) addFormDataPart("caption", caption.take(1024)) }
                .addFormDataPart(
                    "voice", audioFile.name,
                    audioFile.asRequestBody("audio/mpeg".toMediaType())
                )
                .build()
            val request = Request.Builder()
                .url("$baseUrl/sendVoice")
                .post(body)
                .build()
            withContext(Dispatchers.IO) {
                try { client.newCall(request).execute().close() }
                catch (_: IOException) { /* network blip — voice silently skipped */ }
            }
        } catch (_: Exception) {
            // Voice failures must never break the user-visible text reply.
        }
    }

    suspend fun downloadAttachment(attachment: TelegramAttachment): DownloadedTelegramAttachment? {
        val fileInfoRequest = Request.Builder()
            .url("$baseUrl/getFile?file_id=${attachment.fileId}")
            .build()

        val filePath = withContext(Dispatchers.IO) {
            try {
                client.newCall(fileInfoRequest).execute().use { response ->
                    val bodyStr = response.body?.string().orEmpty()
                    if (!response.isSuccessful) return@use null
                    val json = gson.fromJson(bodyStr, JsonObject::class.java)
                    if (json?.get("ok")?.asBoolean != true) return@use null
                    json.getAsJsonObject("result")
                        ?.get("file_path")
                        ?.asString
                }
            } catch (_: Exception) {
                null
            }
        } ?: return null

        val targetFile = File(downloadDir, buildLocalFileName(attachment, filePath))
        val downloadRequest = Request.Builder()
            .url("https://api.telegram.org/file/bot$botToken/$filePath")
            .build()

        val downloaded = withContext(Dispatchers.IO) {
            try {
                client.newCall(downloadRequest).execute().use { response ->
                    if (!response.isSuccessful) return@use false
                    val body = response.body ?: return@use false
                    targetFile.outputStream().use { output ->
                        body.byteStream().use { input -> input.copyTo(output) }
                    }
                    true
                }
            } catch (_: Exception) {
                false
            }
        }
        if (!downloaded) return null

        return DownloadedTelegramAttachment(
            attachment = attachment,
            file = targetFile,
            displayName = attachment.fileName.ifBlank { targetFile.name },
            mimeType = attachment.mimeType.ifBlank { inferMimeType(targetFile.name) },
        )
    }

    fun stop() = scope.cancel()

    // ── Internal ──────────────────────────────

    private fun buildCommandKeyboard(): Map<String, Any>? {
        if (commands.isEmpty()) return null

        val priorityOrder = listOf(
            "help", "info", "research",
            "summarize",
            "status", "skills", "history",
            "memory", "knowledge", "mode",
            "muted", "clear"
        )

        val byName = commands.associateBy { it.command }
        val orderedButtons = priorityOrder
            .mapNotNull { byName[it] }
            .map { "/${it.command}" } +
            commands
                .map { it.command }
                .filterNot { it in priorityOrder }
                .map { "/$it" }

        if (orderedButtons.isEmpty()) return null

        val rows = orderedButtons.chunked(3)
        return mapOf(
            "keyboard" to rows.map { row -> row.map { command -> mapOf("text" to command) } },
            "resize_keyboard" to true,
            "is_persistent" to true,
            "one_time_keyboard" to false,
            "input_field_placeholder" to "Choose a Kaivor command",
        )
    }

    private suspend fun syncCommandMenu(): Boolean {
        if (commands.isEmpty()) return true

        fun commandMaps(): List<Map<String, String>> = commands.map { command ->
            mapOf(
                "command" to command.command,
                "description" to command.description,
            )
        }

        var synced = postJson(
            "$baseUrl/setMyCommands",
            mapOf("commands" to commandMaps())
        )

        synced = postJson(
            "$baseUrl/setMyCommands",
            mapOf(
                "scope" to mapOf("type" to "all_private_chats"),
                "commands" to commandMaps(),
            )
        ) && synced

        authorizedChatIds.forEach { chatId ->
            synced = postJson(
                "$baseUrl/setMyCommands",
                mapOf(
                    "scope" to mapOf(
                        "type" to "chat",
                        "chat_id" to chatId,
                    ),
                    "commands" to commandMaps(),
                )
            ) && synced
        }

        val menuPayload = mapOf(
            "menu_button" to mapOf("type" to "commands")
        )
        synced = postJson("$baseUrl/setChatMenuButton", menuPayload) && synced

        authorizedChatIds.forEach { chatId ->
            synced = postJson(
                "$baseUrl/setChatMenuButton",
                mapOf(
                    "chat_id" to chatId,
                    "menu_button" to mapOf("type" to "commands")
                )
            ) && synced
        }

        return synced
    }

    private suspend fun postJson(url: String, payload: Any): Boolean {
        val request = Request.Builder()
            .url(url)
            .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
            .build()

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string().orEmpty()
                    if (!response.isSuccessful) return@use false
                    val json = gson.fromJson(bodyStr, JsonObject::class.java)
                    json?.get("ok")?.asBoolean == true
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    /** Send raw image bytes (PNG, JPEG, WebP) as a Telegram photo — appears inline in chat. */
    suspend fun sendPhotoBytes(chatId: Long, bytes: ByteArray, caption: String = "", mimeType: String = "image/png") {
        val ext = when {
            mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
            mimeType.contains("webp") -> "webp"
            else -> "png"
        }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId.toString())
            .addFormDataPart("caption", caption.take(1024))
            .addFormDataPart("photo", "image.$ext", bytes.toRequestBody(mimeType.toMediaType()))
            .apply {
                buildCommandKeyboard()?.let { addFormDataPart("reply_markup", gson.toJson(it)) }
            }
            .build()
        val request = Request.Builder().url("$baseUrl/sendPhoto").post(body).build()
        withContext(Dispatchers.IO) {
            try { client.newCall(request).execute().close() }
            catch (_: IOException) { sendMessage(chatId, caption.ifBlank { "📷 Image generated." }) }
        }
    }

    /** Send raw bytes as a file/document — user can download it from Telegram. */
    suspend fun sendDocumentBytes(
        chatId: Long,
        bytes: ByteArray,
        filename: String,
        caption: String = "",
        mimeType: String = "application/octet-stream",
    ) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId.toString())
            .addFormDataPart("caption", caption.take(1024))
            .addFormDataPart("document", filename, bytes.toRequestBody(mimeType.toMediaType()))
            .apply {
                buildCommandKeyboard()?.let { addFormDataPart("reply_markup", gson.toJson(it)) }
            }
            .build()
        val request = Request.Builder().url("$baseUrl/sendDocument").post(body).build()
        withContext(Dispatchers.IO) {
            try { client.newCall(request).execute().close() }
            catch (_: IOException) { sendMessage(chatId, caption.ifBlank { "📎 File generated." }) }
        }
    }

    suspend fun sendLongMessage(chatId: Long, text: String, parseMode: String? = null) {
        val chunks = splitMessage(text)
        chunks.forEachIndexed { index, chunk ->
            val prefix = if (chunks.size > 1) "Part ${index + 1}/${chunks.size}\n\n" else ""
            sendMessage(chatId, prefix + chunk, parseMode)
        }
    }

    private fun splitMessage(text: String, maxChars: Int = 3500): List<String> {
        val cleaned = text.trim()
        if (cleaned.isBlank()) return emptyList()
        if (cleaned.length <= maxChars) return listOf(cleaned)

        val chunks = mutableListOf<String>()
        var remaining = cleaned
        while (remaining.length > maxChars) {
            val splitAt = listOf(
                remaining.lastIndexOf("\n\n", maxChars),
                remaining.lastIndexOf('\n', maxChars),
                remaining.lastIndexOf(' ', maxChars),
            ).firstOrNull { it >= maxChars / 2 } ?: maxChars
            chunks += remaining.substring(0, splitAt).trim()
            remaining = remaining.substring(splitAt).trim()
        }
        if (remaining.isNotBlank()) chunks += remaining
        return chunks
    }

    private fun buildLocalFileName(attachment: TelegramAttachment, filePath: String): String {
        val originalName = attachment.fileName.ifBlank { filePath.substringAfterLast('/') }
        val safeName = originalName
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { attachment.uniqueId.ifBlank { "telegram_file" } }
        val prefix = attachment.uniqueId.ifBlank { attachment.fileId.takeLast(8) }
        return "${prefix}_${safeName}"
    }

    private fun inferMimeType(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "ogg", "oga", "opus" -> "audio/ogg"
            "mp3" -> "audio/mpeg"
            "m4a", "mp4" -> "audio/mp4"
            "wav" -> "audio/wav"
            "webm" -> "audio/webm"
            "txt", "md", "csv", "json", "xml", "log" -> "text/plain"
            "html", "htm" -> "text/html"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
    }

    private fun parseDocumentAttachment(message: JsonObject): TelegramAttachment? {
        val document = message.getAsJsonObject("document") ?: return null
        return TelegramAttachment(
            kind = TelegramAttachmentKind.DOCUMENT,
            fileId = document.get("file_id")?.asString ?: return null,
            uniqueId = document.get("file_unique_id")?.asString.orEmpty(),
            fileName = document.get("file_name")?.asString.orEmpty(),
            mimeType = document.get("mime_type")?.asString.orEmpty(),
            sizeBytes = document.get("file_size")?.asLong ?: 0L,
        )
    }

    private fun parseVoiceAttachment(message: JsonObject): TelegramAttachment? {
        val voice = message.getAsJsonObject("voice") ?: return null
        return TelegramAttachment(
            kind = TelegramAttachmentKind.AUDIO,
            fileId = voice.get("file_id")?.asString ?: return null,
            uniqueId = voice.get("file_unique_id")?.asString.orEmpty(),
            fileName = "voice_${voice.get("file_unique_id")?.asString.orEmpty()}.ogg",
            mimeType = voice.get("mime_type")?.asString ?: "audio/ogg",
            sizeBytes = voice.get("file_size")?.asLong ?: 0L,
        )
    }

    private fun parseAudioAttachment(message: JsonObject): TelegramAttachment? {
        val audio = message.getAsJsonObject("audio") ?: return null
        val fileName = audio.get("file_name")?.asString.orEmpty()
        return TelegramAttachment(
            kind = TelegramAttachmentKind.AUDIO,
            fileId = audio.get("file_id")?.asString ?: return null,
            uniqueId = audio.get("file_unique_id")?.asString.orEmpty(),
            fileName = fileName.ifBlank { "audio_${audio.get("file_unique_id")?.asString.orEmpty()}" },
            mimeType = audio.get("mime_type")?.asString ?: inferMimeType(fileName),
            sizeBytes = audio.get("file_size")?.asLong ?: 0L,
        )
    }

    private fun parsePhotoAttachment(message: JsonObject): TelegramAttachment? {
        val photos = message.getAsJsonArray("photo") ?: return null
        val bestPhoto = photos
            .map { it.asJsonObject }
            .maxByOrNull { it.get("file_size")?.asLong ?: 0L }
            ?: return null
        return TelegramAttachment(
            kind = TelegramAttachmentKind.PHOTO,
            fileId = bestPhoto.get("file_id")?.asString ?: return null,
            uniqueId = bestPhoto.get("file_unique_id")?.asString.orEmpty(),
            fileName = "photo_${bestPhoto.get("file_unique_id")?.asString.orEmpty()}.jpg",
            mimeType = "image/jpeg",
            sizeBytes = bestPhoto.get("file_size")?.asLong ?: 0L,
        )
    }

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
                    val text = message.get("text")?.asString
                        ?: message.get("caption")?.asString
                        ?: ""
                    val from = message.getAsJsonObject("from")
                    val attachment = parseVoiceAttachment(message)
                        ?: parseAudioAttachment(message)
                        ?: parseDocumentAttachment(message)
                        ?: parsePhotoAttachment(message)
                    if (text.isBlank() && attachment == null) return@mapNotNull null

                    val replyTo = message.getAsJsonObject("reply_to_message")
                        ?.get("message_id")?.asLong

                    IncomingMessage(
                        updateId = update.get("update_id").asLong,
                        chatId = chat.get("id").asLong,
                        username = from?.get("username")?.asString,
                        text = text,
                        attachment = attachment,
                        replyToMessageId = replyTo,
                    )
                }
            }
        }
    }
}
