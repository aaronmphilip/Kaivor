package com.kaivor.agent

import android.content.Context
import android.media.*
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Real-time audio bridge for AI-answered phone calls.
 *
 * Pipeline:
 *   AudioRecord (VOICE_COMMUNICATION) → VAD chunks → Whisper STT → LLM → ElevenLabs PCM TTS → AudioTrack
 *
 * Volume strategy:
 *   - Speaker at 40% while listening: quiet in room, mic still captures caller voice cleanly
 *   - Speaker at 15% while AI speaks: reduces echo artifact into mic
 *   - Hardware echo cancellation (VOICE_COMMUNICATION source) handles the rest
 *
 * Mid-call owner-request detection:
 *   - Caller says "Can I speak to [name] / is the owner available / put me through" →
 *     onOwnerRequested fires instantly → AI says holding response →
 *     owner gets urgent Telegram alert
 *
 * Post-call:
 *   - Full conversation transcript sent first, then LLM-generated summary
 */
class CallAudioBridge(
    private val context: Context,
    private val elevenLabsKey: String,
    private val elevenLabsVoiceId: String,
    private val sttApiKey: String,
    private val llmKey: String,
    private val llmProvider: AIProvider,
    private val llmModel: String,
    private val ownerName: String,
    private val callerName: String,
    private val onTranscript: suspend (callerText: String, aiText: String) -> Unit,
    private val onOwnerRequested: suspend () -> Unit,
    private val onCallEnded: suspend (fullTranscript: String, summary: String) -> Unit,
    // ── Outbound-call extensions ───────────────────────────────────────────────
    private val isOutbound: Boolean = false,
    private val outboundBriefing: String = "",
    /** Fires when AI needs to relay a question to the owner. Suspends until owner replies. */
    private val onOwnerQuery: (suspend (question: String) -> String)? = null,
    /** Fires when AI signals [CALL_END] — caller disconnects cleanly. */
    private val onCallShouldEnd: (() -> Unit)? = null,
) {
    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val VAD_SILENCE_MS = 1_200L
        private const val VAD_MIN_SPEECH_BYTES = SAMPLE_RATE * 2   // 0.5 s of 16-bit mono
        private const val RMS_SPEECH_THRESHOLD = 240.0
        private const val SPEAKER_LISTEN   = 0.40f   // 40% — quiet but mic picks it up
        private const val SPEAKER_SPEAKING = 0.15f   // 15% — suppress echo while AI talks

        // Keywords that signal the caller wants to speak directly to the owner.
        // Checked case-insensitively before every LLM call to avoid extra API calls.
        private val OWNER_REQUEST_PHRASES = listOf(
            "speak to", "talk to", "put me through", "connect me",
            "can i reach", "is the owner", "owner available", "talk to the owner",
            "speak to them", "speak to him", "speak to her",
            "is he available", "is she available", "call them back",
            "when are they free", "when is he free", "when is she free",
            "transfer me", "let me talk",
        )

        @Volatile var instance: CallAudioBridge? = null
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val http = OkHttpClient.Builder()
        .callTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
    private val llm = LLMClient(apiKey = llmKey, provider = llmProvider, model = llmModel)
    private val history = mutableListOf<AIChatMessage>()

    @Volatile private var running = false
    @Volatile private var aiSpeaking = false
    @Volatile private var ownerAlertSent = false  // send at most once per call
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var audioManager: AudioManager? = null
    private var originalVolume = -1
    private var maxVolume = -1

    // ── Public ─────────────────────────────────────────────────────────────────

    fun start() {
        running = true
        instance = this
        initAudio()
        scope.launch {
            val greeting = generateResponse(null)
            history.add(AIChatMessage("assistant", greeting))
            speakToCall(greeting)
            captureLoop()
        }
    }

    fun stop() {
        running = false
        if (instance === this) instance = null
        // Build and send transcript + summary before cancelling scope
        val transcriptSnapshot = history.toList()
        scope.launch {
            val fullText = buildFullTranscript(transcriptSnapshot)
            val summary = buildSummary(transcriptSnapshot)
            onCallEnded(fullText, summary)
        }
        // Cancel after a brief window for the above to complete
        scope.launch {
            delay(8_000)
            scope.cancel()
        }
        restoreVolume()
        runCatching { recorder?.stop(); recorder?.release() }
        runCatching { player?.stop(); player?.release() }
        recorder = null; player = null
    }

    // ── Audio init ─────────────────────────────────────────────────────────────

    private fun initAudio() {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager!!.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        originalVolume = audioManager!!.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        setVolumeFraction(SPEAKER_LISTEN)

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, SAMPLE_RATE * 4),
            )
        }.getOrNull()

        val outRate = if (elevenLabsKey.isNotBlank() && elevenLabsVoiceId.isNotBlank()) 24_000 else SAMPLE_RATE
        val outBuf = AudioTrack.getMinBufferSize(outRate,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4
        player = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(outRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(outBuf)
                .build()
        }.getOrNull()

        recorder?.startRecording()
        player?.play()
    }

    private fun setVolumeFraction(f: Float) {
        if (maxVolume <= 0) return
        audioManager?.setStreamVolume(AudioManager.STREAM_VOICE_CALL,
            (maxVolume * f).toInt().coerceIn(0, maxVolume), 0)
    }

    private fun restoreVolume() {
        if (originalVolume >= 0)
            audioManager?.setStreamVolume(AudioManager.STREAM_VOICE_CALL, originalVolume, 0)
    }

    // ── Capture loop ───────────────────────────────────────────────────────────

    private suspend fun captureLoop() {
        val chunkSize = SAMPLE_RATE * 2 / 10   // 100 ms
        val buf = ByteArray(chunkSize)
        val accumulator = ByteArrayOutputStream()
        var lastSoundMs = 0L
        var hasSpeech = false

        while (running) {
            if (aiSpeaking) { delay(80); continue }

            val rec = recorder ?: break
            val read = rec.read(buf, 0, chunkSize)
            if (read <= 0) { delay(10); continue }

            val rms = rms(buf, read)
            if (rms > RMS_SPEECH_THRESHOLD) {
                hasSpeech = true
                lastSoundMs = System.currentTimeMillis()
                accumulator.write(buf, 0, read)
            } else if (hasSpeech) {
                accumulator.write(buf, 0, read)
                if (System.currentTimeMillis() - lastSoundMs >= VAD_SILENCE_MS) {
                    val audio = accumulator.toByteArray()
                    accumulator.reset(); hasSpeech = false; lastSoundMs = 0L
                    if (audio.size >= VAD_MIN_SPEECH_BYTES) processUtterance(audio)
                }
            }
        }
    }

    private suspend fun processUtterance(pcm: ByteArray) {
        val wav = pcmToWav(pcm)
        val transcript = transcribeWhisper(wav)?.takeIf { it.length >= 3 } ?: return
        history.add(AIChatMessage("user", transcript))

        val lower = transcript.lowercase()

        // ── Inbound: owner-request detection ────────────────────────────────────
        // Fast keyword check (no LLM cost). Fire at most once per call.
        val wantsOwner = !isOutbound && !ownerAlertSent && OWNER_REQUEST_PHRASES.any { lower.contains(it) }

        val rawAiReply = if (wantsOwner) {
            ownerAlertSent = true
            scope.launch { onOwnerRequested() }
            val displayName = ownerName.ifBlank { "them" }
            "Let me check if $displayName is available right now — one moment please."
        } else {
            generateResponse(transcript)
        }

        // ── Outbound: parse sentinels ────────────────────────────────────────────
        // [ASK_OWNER: question] → block, relay to owner via Telegram, feed answer back
        // [CALL_END]            → speak goodbye, then trigger clean hang-up
        if (isOutbound && rawAiReply.contains("[ASK_OWNER:")) {
            val question = rawAiReply.substringAfter("[ASK_OWNER:").substringBefore("]").trim()
            val cleanReply = rawAiReply.replace(Regex("\\[ASK_OWNER:[^]]*]"), "").trim()
            history.add(AIChatMessage("assistant", cleanReply))
            onTranscript(transcript, cleanReply)
            speakToCall(cleanReply)

            // Block until owner types a reply (or 120s timeout)
            val ownerAnswer = try { onOwnerQuery?.invoke(question) } catch (_: Exception) { null }
            if (!ownerAnswer.isNullOrBlank()) {
                // Feed owner's answer into conversation as a hidden system note
                val ownerNote = "[${ownerName.ifBlank { "Owner" }} says: $ownerAnswer]"
                history.add(AIChatMessage("user", ownerNote))
                val followUp = generateResponse(ownerNote)
                history.add(AIChatMessage("assistant", followUp))
                onTranscript("", followUp)
                speakToCall(followUp)
            }
            return
        }

        if (isOutbound && rawAiReply.trimEnd().endsWith("[CALL_END]")) {
            val goodbye = rawAiReply.removeSuffix("[CALL_END]").trim()
            history.add(AIChatMessage("assistant", goodbye))
            onTranscript(transcript, goodbye)
            if (goodbye.isNotBlank()) speakToCall(goodbye)
            delay(1_200)
            onCallShouldEnd?.invoke()
            return
        }

        history.add(AIChatMessage("assistant", rawAiReply))
        onTranscript(transcript, rawAiReply)
        speakToCall(rawAiReply)
    }

    // ── LLM ────────────────────────────────────────────────────────────────────

    private suspend fun generateResponse(input: String?): String {
        val display = ownerName.ifBlank { "the owner" }

        val systemPrompt = when {
            // ── Outbound mode ────────────────────────────────────────────────────
            isOutbound && input == null -> {
                // Opening greeting — keep it crisp
                "You are $display's AI assistant making an outbound phone call. " +
                "Purpose of this call: $outboundBriefing. " +
                "Generate a warm, natural opening: introduce yourself as \"$display's AI assistant\", " +
                "state your purpose in one sentence, and ask if it is a good time. " +
                "Maximum 2 sentences. Sound human, not robotic."
            }
            isOutbound -> {
                // Ongoing outbound conversation — include sentinel instructions
                "You are $display's AI assistant conducting an outbound call. " +
                "Purpose of this call: $outboundBriefing. " +
                "Keep replies under 30 words unless you are explaining something important. " +
                "Be natural, warm, professional. " +
                "SENTINEL RULES (strictly follow):\n" +
                "• If you need to check something with $display that you cannot answer yourself, " +
                "include exactly [ASK_OWNER: your question for $display] in your reply (verbatim brackets), " +
                "preceded by a holding phrase like 'Let me check that with $display — one moment.'.\n" +
                "• When the call is complete (purpose achieved, caller says bye/no thanks/nothing else, " +
                "or after you say goodbye), append exactly [CALL_END] at the very end of your response.\n" +
                "• Never include both sentinels in the same response."
            }
            // ── Inbound mode ─────────────────────────────────────────────────────
            input == null -> {
                "You are $display's AI assistant answering their phone calls. " +
                "Greet the caller and introduce yourself as \"$display's AI assistant\". " +
                "Let them know $display is currently unavailable but you can take a message or help. " +
                "Sound natural, warm, human. 1-2 sentences only."
            }
            else -> {
                "You are $display's AI assistant answering their phone calls. " +
                "Be natural, brief, warm. Take messages, answer simple questions, " +
                "say $display will call back soon. Keep replies under 25 words."
            }
        }
        val msgs = if (input == null) emptyList() else history.takeLast(10)
        return try {
            llm.generateText(systemPrompt, msgs, temperature = 0.75)
                .getOrNull()?.trim() ?: "Could you repeat that?"
        } catch (_: Exception) { "One moment please." }
    }

    private suspend fun buildFullTranscript(history: List<AIChatMessage>): String {
        if (history.isEmpty()) return "No conversation recorded."
        return history.joinToString("\n") { m ->
            if (m.role == "user") "📞 Caller: ${m.content}"
            else "🤖 AI: ${m.content}"
        }
    }

    private suspend fun buildSummary(history: List<AIChatMessage>): String {
        if (history.isEmpty()) return "No conversation to summarize."
        val transcript = history.joinToString("\n") { m ->
            if (m.role == "user") "Caller: ${m.content}" else "AI: ${m.content}"
        }
        return try {
            llm.generateText(
                systemPrompt = "You summarize phone calls briefly.",
                messages = listOf(AIChatMessage("user",
                    "Summarize this call in 2-3 sentences. What did the caller want? What was resolved?\n\n$transcript")),
                temperature = 0.3,
            ).getOrNull()?.trim() ?: "Call ended."
        } catch (_: Exception) { "Call ended. Summary unavailable." }
    }

    // ── ElevenLabs TTS ─────────────────────────────────────────────────────────

    private suspend fun speakToCall(text: String) = withContext(Dispatchers.IO) {
        if (elevenLabsKey.isBlank() || elevenLabsVoiceId.isBlank()) return@withContext
        aiSpeaking = true
        setVolumeFraction(SPEAKER_SPEAKING)
        runCatching {
            val json = """{"text":${JSONObject.quote(text)},"model_id":"eleven_turbo_v2_5","voice_settings":{"stability":0.45,"similarity_boost":0.82}}"""
            val req = Request.Builder()
                .url("https://api.elevenlabs.io/v1/text-to-speech/$elevenLabsVoiceId?output_format=pcm_24000")
                .header("xi-api-key", elevenLabsKey)
                .header("Accept", "audio/pcm")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use
                val bytes = resp.body?.bytes() ?: return@use
                val pcm = ShortArray(bytes.size / 2) { i ->
                    ((bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
                }
                player?.write(pcm, 0, pcm.size)
            }
        }
        delay(300)   // let echo canceller settle
        setVolumeFraction(SPEAKER_LISTEN)
        aiSpeaking = false
    }

    // ── Whisper STT ────────────────────────────────────────────────────────────

    private suspend fun transcribeWhisper(wav: ByteArray): String? {
        if (sttApiKey.isBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
                    .addFormDataPart("model", "whisper-1")
                    .addFormDataPart("language", "en")
                    .build()
                val req = Request.Builder()
                    .url("https://api.openai.com/v1/audio/transcriptions")
                    .header("Authorization", "Bearer $sttApiKey")
                    .post(body)
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    JSONObject(resp.body?.string() ?: "{}").optString("text").takeIf { it.isNotBlank() }
                }
            }.getOrNull()
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun rms(buf: ByteArray, len: Int): Double {
        var sum = 0L; var i = 0
        while (i < len - 1) {
            val s = ((buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)).toShort().toInt()
            sum += s.toLong() * s; i += 2
        }
        return sqrt(sum.toDouble() / (len / 2))
    }

    private fun pcmToWav(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
        fun w32(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun w16(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        out.write("RIFF".toByteArray()); w32(36 + pcm.size)
        out.write("WAVEfmt ".toByteArray()); w32(16); w16(1); w16(1)
        w32(SAMPLE_RATE); w32(SAMPLE_RATE * 2); w16(2); w16(16)
        out.write("data".toByteArray()); w32(pcm.size); out.write(pcm)
        return out.toByteArray()
    }
}
