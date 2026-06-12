package com.kaivor.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.roundToInt

private data class SummaryRequest(
    val originalInstruction: String,
    val outputGuidance: String,
)

private data class RenderedPage(
    val pageNumber: Int,
    val bitmap: Bitmap,
)

private data class PdfRenderResult(
    val pages: List<RenderedPage>,
    val totalPages: Int,
)

class DocumentSummaryBrain(
    context: Context,
    apiKey: String,
    provider: AIProvider,
    model: String = "",
) {
    companion object {
        private const val DIRECT_TEXT_LIMIT = 60_000
        private const val NOTE_CHUNK_LIMIT = 20_000
        private const val MAX_TEXT_CHUNKS = 100
        private const val MAX_PDF_VISION_PAGES = 100
        private const val VISION_BATCH_SIZE = 5
        private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        private val TEXT_EXTENSIONS = setOf("txt", "md", "markdown", "csv", "json", "xml", "log")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

        fun fromContext(context: Context): DocumentSummaryBrain? {
            val prefs = context.getSharedPreferences("kaivor", Context.MODE_PRIVATE)
            val apiKey = prefs.getString("agent_ai_key", prefs.getString("ai_key", ""))?.trim().orEmpty()
            if (apiKey.isBlank()) return null

            val providerName = prefs.getString("agent_ai_provider", prefs.getString("ai_provider", ""))?.trim().orEmpty()
            val provider = try {
                if (providerName.isNotBlank()) AIProvider.valueOf(providerName)
                else AIBrain.detectProvider(apiKey)
            } catch (_: Exception) {
                AIBrain.detectProvider(apiKey)
            }
            val model = prefs.getString("agent_ai_model", prefs.getString("ai_model", "")) ?: ""
            return DocumentSummaryBrain(context, apiKey, provider, model)
        }
    }

    private val appContext = context.applicationContext
    private val llm = LLMClient(apiKey = apiKey, provider = provider, model = model)

    init {
        runCatching { PDFBoxResourceLoader.init(appContext) }
    }

    suspend fun summarizeFile(
        file: File,
        mimeType: String = "",
        displayName: String = file.name,
        instruction: String = "",
    ): String {
        val request = parseSummaryRequest(instruction)
        val name = displayName.ifBlank { file.name.ifBlank { "document" } }
        val normalizedMimeType = normalizeMimeType(mimeType, name)
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

        val extractedText = when {
            normalizedMimeType == "application/pdf" || extension == "pdf" -> extractPdfText(file)
            normalizedMimeType == DOCX_MIME || extension == "docx" -> extractDocxText(file)
            looksLikeHtml(normalizedMimeType, extension) -> extractHtmlText(file)
            looksLikePlainText(normalizedMimeType, extension) -> readPlainText(file)
            else -> ""
        }

        if (looksReadableText(extractedText)) {
            return summarizeTextDocument(name, extractedText, request)
        }

        return when {
            normalizedMimeType == "application/pdf" || extension == "pdf" -> {
                val rendered = renderPdfPages(file)
                if (rendered.pages.isEmpty()) {
                    "I downloaded $name, but I could not read text from it yet."
                } else {
                    val coverageNote = if (rendered.totalPages > rendered.pages.size) {
                        "This PDF appears image-based, so I visually analyzed the first ${rendered.pages.size} pages out of ${rendered.totalPages}."
                    } else ""
                    summarizeVisualPages(name, rendered.pages, request, coverageNote)
                }
            }

            normalizedMimeType.startsWith("image/") || extension in IMAGE_EXTENSIONS -> {
                val bitmap = decodeImage(file)
                    ?: return "I downloaded $name, but I could not open the image."
                summarizeVisualPages(name, listOf(RenderedPage(1, bitmap)), request)
            }

            else -> "I can summarize PDF, DOCX, TXT, Markdown, HTML, JSON, CSV, XML, and image files right now. I downloaded $name but could not read that format yet."
        }
    }

    suspend fun summarizeVisibleText(
        documentName: String,
        visibleText: String,
        instruction: String = "",
    ): String {
        return summarizeTextDocument(
            documentName = documentName,
            rawText = visibleText,
            request = parseSummaryRequest(instruction),
        )
    }

    suspend fun summarizeVisibleScreens(
        documentName: String,
        screenshots: List<Bitmap>,
        instruction: String = "",
        coverageNote: String = "",
    ): String {
        val pages = screenshots.mapIndexed { index, bitmap ->
            RenderedPage(pageNumber = index + 1, bitmap = bitmap)
        }
        if (pages.isEmpty()) {
            return "I could open $documentName, but I couldn't capture enough screens to summarize it."
        }
        return summarizeVisualPages(
            documentName = documentName,
            pages = pages,
            request = parseSummaryRequest(instruction),
            coverageNote = coverageNote.ifBlank {
                "This summary is based on ${pages.size} visible screen${if (pages.size != 1) "s" else ""} captured on the phone."
            },
        )
    }

    private suspend fun summarizeTextDocument(
        documentName: String,
        rawText: String,
        request: SummaryRequest,
    ): String {
        val cleanedText = normalizeDocumentText(rawText)
        if (cleanedText.isBlank()) {
            return "I could open $documentName, but there was no readable text to summarize."
        }

        var coverageNote = ""
        val summary = if (cleanedText.length <= DIRECT_TEXT_LIMIT) {
            summarizeMaterial(documentName, cleanedText, request)
        } else {
            val chunks = splitIntoChunks(cleanedText, NOTE_CHUNK_LIMIT)
            val cappedChunks = chunks.take(MAX_TEXT_CHUNKS)
            val notes = cappedChunks.mapIndexedNotNull { index, chunk ->
                createTextChunkNotes(documentName, chunk, index + 1, cappedChunks.size)
                    .takeIf { it.isNotBlank() }
            }

            coverageNote = if (chunks.size > cappedChunks.size) {
                "I summarized the first ${cappedChunks.size} sections because the document is very long."
            } else ""

            summarizeMaterial(
                documentName = documentName,
                material = if (notes.isEmpty()) cleanedText.take(DIRECT_TEXT_LIMIT) else notes.joinToString("\n\n"),
                request = request,
                coverageNote = coverageNote,
            )
        }

        return formatSummary(documentName, summary, coverageNote)
    }

    private suspend fun summarizeVisualPages(
        documentName: String,
        pages: List<RenderedPage>,
        request: SummaryRequest,
        coverageNote: String = "",
    ): String {
        val notes = pages.chunked(VISION_BATCH_SIZE).mapNotNull { batch ->
            createVisionBatchNotes(documentName, batch).takeIf { it.isNotBlank() }
        }
        if (notes.isEmpty()) {
            return "I could open $documentName, but I could not read enough from the pages to summarize it."
        }

        val summary = summarizeMaterial(
            documentName = documentName,
            material = notes.joinToString("\n\n"),
            request = request,
            coverageNote = coverageNote,
        )
        return formatSummary(documentName, summary, coverageNote)
    }

    private suspend fun summarizeMaterial(
        documentName: String,
        material: String,
        request: SummaryRequest,
        coverageNote: String = "",
    ): String {
        val systemPrompt = """
You are Kaivor's document summarizer.
Summarize only what is supported by the supplied material.
Use the same language as the document or the user's request whenever possible.
Follow the requested format exactly.
Do not invent missing facts.
If there are action items, dates, people, numbers, deadlines, or decisions, include them.
Avoid markdown tables.
        """.trimIndent()

        val prompt = buildString {
            appendLine("Document: $documentName")
            appendLine("User request: ${request.originalInstruction}")
            appendLine("Output guidance: ${request.outputGuidance}")
            if (coverageNote.isNotBlank()) appendLine("Coverage note: $coverageNote")
            appendLine()
            appendLine("Material:")
            append(material.take(48_000))
        }

        val summary = llm.generateText(
            systemPrompt = systemPrompt,
            messages = listOf(AIChatMessage(role = "user", content = prompt)),
            temperature = 0.2,
        ).getOrNull().orEmpty().trim()

        return summary.ifBlank { buildFallbackSummary(material, coverageNote) }
    }

    private suspend fun createTextChunkNotes(
        documentName: String,
        chunk: String,
        index: Int,
        total: Int,
    ): String {
        val systemPrompt = """
You are Kaivor's document analyst.
Read the supplied chunk and extract compact notes.
Preserve headings, names, dates, amounts, deadlines, obligations, risks, and action items.
If the chunk is mostly boilerplate, say that briefly.
Plain text only.
        """.trimIndent()

        val prompt = """
Document: $documentName
Chunk: $index/$total

$chunk
        """.trimIndent()

        return llm.generateText(
            systemPrompt = systemPrompt,
            messages = listOf(AIChatMessage(role = "user", content = prompt)),
            temperature = 0.1,
        ).getOrNull().orEmpty().trim()
    }

    private suspend fun createVisionBatchNotes(
        documentName: String,
        pages: List<RenderedPage>,
    ): String {
        val systemPrompt = """
You are Kaivor's visual document analyst.
Read the supplied page images carefully and extract compact notes.
Preserve headings, names, dates, amounts, deadlines, action items, and decisions.
If some text is unclear, say so briefly instead of guessing.
Plain text only.
        """.trimIndent()

        val images = pages.mapNotNull { bitmapToVisionImage(it.bitmap) }
        if (images.isEmpty()) return ""

        val prompt = """
Document: $documentName
Pages shown: ${pages.joinToString(", ") { it.pageNumber.toString() }}

Extract concise notes from these pages.
        """.trimIndent()

        return llm.generateVisionText(
            systemPrompt = systemPrompt,
            prompt = prompt,
            images = images,
            temperature = 0.1,
        ).getOrNull().orEmpty().trim()
    }

    private fun parseSummaryRequest(instruction: String): SummaryRequest {
        val cleaned = instruction.trim().ifBlank {
            "Give me a concise summary with the key points, action items, deadlines, and important numbers."
        }
        val lower = cleaned.lowercase(Locale.ROOT)
        val guidance = when {
            containsAny(lower, "one paragraph", "1 paragraph", "single paragraph", "one para", "1 para") ->
                "Write exactly one paragraph of roughly 120 to 180 words."
            containsAny(lower, "two page", "2 page", "two-page", "2-page") ->
                "Write a detailed summary of about 900 to 1200 words with short section headings."
            containsAny(lower, "one page", "1 page", "single page", "one-page", "1-page") ->
                "Write about 450 to 650 words, readable as a one-page summary."
            containsAny(lower, "bullet", "bullets", "bullet points", "key points") ->
                "Write a concise bullet-style summary with the most important points first."
            containsAny(lower, "detailed", "detail", "deep") ->
                "Write a detailed summary with short section headings and include action items, risks, dates, numbers, and decisions."
            else -> "Write a concise but useful summary in 3 to 6 short paragraphs."
        }
        return SummaryRequest(cleaned, guidance)
    }

    private fun normalizeMimeType(mimeType: String, fileName: String): String {
        val explicit = mimeType.trim().lowercase(Locale.ROOT)
        if (explicit.isNotBlank()) return explicit

        return when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "pdf" -> "application/pdf"
            "docx" -> DOCX_MIME
            "txt", "md", "markdown", "csv", "json", "xml", "log" -> "text/plain"
            "html", "htm" -> "text/html"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
    }

    private fun looksLikePlainText(mimeType: String, extension: String): Boolean {
        return mimeType.startsWith("text/") || mimeType in setOf("application/json", "application/xml") || extension in TEXT_EXTENSIONS
    }

    private fun looksLikeHtml(mimeType: String, extension: String): Boolean {
        return mimeType == "text/html" || extension in setOf("html", "htm")
    }

    private fun looksReadableText(text: String): Boolean {
        val cleaned = normalizeDocumentText(text)
        return cleaned.length >= 120 && cleaned.any { it.isLetter() }
    }

    private fun readPlainText(file: File): String = runCatching { file.readText() }.getOrDefault("")

    private fun extractHtmlText(file: File): String {
        val html = readPlainText(file)
        if (html.isBlank()) return ""
        return Jsoup.parse(html).text()
    }

    private fun extractDocxText(file: File): String {
        return try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("word/document.xml") ?: return ""
                zip.getInputStream(entry).bufferedReader().use { reader ->
                    Jsoup.parse(reader.readText(), "", Parser.xmlParser()).text()
                }
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractPdfText(file: File): String {
        return try {
            PDDocument.load(file).use { document ->
                PDFTextStripper().getText(document)
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun renderPdfPages(file: File): PdfRenderResult {
        return try {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                val renderer = PdfRenderer(fd)
                try {
                    val pageCount = renderer.pageCount
                    val pages = buildList {
                        val limit = minOf(pageCount, MAX_PDF_VISION_PAGES)
                        for (index in 0 until limit) {
                            val page = renderer.openPage(index)
                            try {
                                val longestSide = max(page.width, page.height).coerceAtLeast(1)
                                val scale = 1400f / longestSide.toFloat()
                                val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                                val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                add(RenderedPage(pageNumber = index + 1, bitmap = bitmap))
                            } finally {
                                page.close()
                            }
                        }
                    }
                    PdfRenderResult(pages, pageCount)
                } finally {
                    renderer.close()
                }
            } finally {
                fd.close()
            }
        } catch (_: Exception) {
            PdfRenderResult(emptyList(), 0)
        }
    }

    private fun decodeImage(file: File): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val longestSide = max(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            var sampleSize = 1
            while (longestSide / sampleSize > 1600) sampleSize *= 2
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun bitmapToVisionImage(bitmap: Bitmap): AIVisionImage? {
        return try {
            val safeBitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                bitmap
            }
            val output = ByteArrayOutputStream()
            safeBitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
            AIVisionImage(
                mimeType = "image/jpeg",
                base64Data = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeDocumentText(text: String): String {
        return text
            .replace('\u0000', ' ')
            .replace(Regex("\\r\\n?"), "\n")
            .replace(Regex("[\\t\\x0B\\f]+"), " ")
            .replace(Regex(" {2,}"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun splitIntoChunks(text: String, maxChars: Int): List<String> {
        val paragraphs = text.split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (paragraphs.isEmpty()) return listOf(text.take(maxChars))

        val chunks = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isNotBlank()) {
                chunks += current.toString().trim()
                current.clear()
            }
        }

        paragraphs.forEach { paragraph ->
            if (paragraph.length > maxChars) {
                flush()
                paragraph.chunked(maxChars).forEach { chunks += it.trim() }
                return@forEach
            }

            val candidateLength = current.length + paragraph.length + if (current.isNotEmpty()) 2 else 0
            if (candidateLength > maxChars) flush()
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(paragraph)
        }

        flush()
        return chunks.ifEmpty { listOf(text.take(maxChars)) }
    }

    private fun formatSummary(documentName: String, summary: String, coverageNote: String = ""): String {
        return buildString {
            append("Summary of ")
            append(documentName)
            append("\n\n")
            append(summary.trim())
            if (coverageNote.isNotBlank() && !summary.contains(coverageNote)) {
                append("\n\nNote: ")
                append(coverageNote)
            }
        }.trim()
    }

    private fun buildFallbackSummary(material: String, coverageNote: String): String {
        val preview = material.replace(Regex("\\s+"), " ").trim().take(1200)
        return buildString {
            append(preview)
            if (coverageNote.isNotBlank()) {
                append("\n\nNote: ")
                append(coverageNote)
            }
        }.trim()
    }

    private fun containsAny(input: String, vararg needles: String): Boolean {
        return needles.any { input.contains(it) }
    }
}
