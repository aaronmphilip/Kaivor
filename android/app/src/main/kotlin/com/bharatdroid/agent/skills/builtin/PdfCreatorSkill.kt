package com.bharatdroid.agent.skills.builtin

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.bharatdroid.agent.skills.Skill
import com.bharatdroid.agent.skills.SkillContext
import com.bharatdroid.agent.skills.SkillManifest
import com.bharatdroid.agent.skills.SkillResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class PdfCreatorSkill : Skill {

    override val manifest = SkillManifest(
        id = "pdf_creator",
        name = "PDF Creator",
        version = "1.0.0",
        description = "Create a PDF document from text content and send it directly in Telegram. Supports headings (# H1, ## H2), bullet points (- item), and plain paragraphs. No API key needed.",
        author = "bharatclaw-team",
        trusted = true,
        permissions = emptySet(),
        exampleParamsHint = """{"title":"My Report","content":"# Introduction\nThis is the intro.\n## Section 1\n- Point one\n- Point two\nSome body text."} | {"title":"Meeting Notes","content":"# Agenda\n- Review Q1\n- Plan Q2","author":"Roni"}""",
    )

    private val PAGE_WIDTH = 595   // A4 at 72dpi
    private val PAGE_HEIGHT = 842
    private val MARGIN = 56
    private val CONTENT_WIDTH = PAGE_WIDTH - MARGIN * 2

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val title = (params["title"] as? String)?.trim()
            ?: return SkillResult.Failure("What should the PDF be titled? Provide a 'title'.")
        val content = (params["content"] as? String
            ?: params["text"] as? String
            ?: params["body"] as? String)?.trim()
            ?: return SkillResult.Failure("What content should go in the PDF? Provide 'content'.")
        val subtitle = (params["subtitle"] as? String)?.trim()
        val author = (params["author"] as? String)?.trim()

        return withContext(Dispatchers.Default) {
            try {
                val bytes = buildPdf(title, subtitle, author, content)
                val safeFilename = title.replace(Regex("[^a-zA-Z0-9_\\- ]"), "").trim()
                    .replace(' ', '_').take(40).ifBlank { "document" } + ".pdf"
                SkillResult.Media(
                    bytes = bytes,
                    caption = "-- $title",
                    mimeType = "application/pdf",
                    filename = safeFilename,
                )
            } catch (e: Exception) {
                SkillResult.Failure("PDF creation failed: ${e.message}")
            }
        }
    }

    private fun buildPdf(title: String, subtitle: String?, author: String?, content: String): ByteArray {
        val doc = PdfDocument()
        val lines = parseContent(title, subtitle, author, content)

        val paintBody = TextPaint().apply {
            color = Color.BLACK
            textSize = 11f
            isAntiAlias = true
        }
        val paintH1 = TextPaint().apply {
            color = Color.BLACK
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        val paintH2 = TextPaint().apply {
            color = Color.BLACK
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        val paintTitle = TextPaint().apply {
            color = Color.BLACK
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        val paintMeta = TextPaint().apply {
            color = Color.GRAY
            textSize = 10f
            isAntiAlias = true
        }
        val paintRule = Paint().apply {
            color = Color.LTGRAY
            strokeWidth = 1f
        }

        var pageNum = 1
        var pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create()
        var page = doc.startPage(pageInfo)
        var canvas: Canvas = page.canvas
        var y = MARGIN.toFloat()

        fun finishPage() {
            doc.finishPage(page)
        }

        fun newPage() {
            finishPage()
            pageNum++
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create()
            page = doc.startPage(info)
            canvas = page.canvas
            y = MARGIN.toFloat()
        }

        fun needsNewPage(height: Float): Boolean {
            return y + height > PAGE_HEIGHT - MARGIN
        }

        fun drawStaticLayout(text: String, paint: TextPaint, indent: Float = 0f): Float {
            val w = (CONTENT_WIDTH - indent).toInt().coerceAtLeast(100)
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .build()
            if (needsNewPage(layout.height.toFloat() + 6)) newPage()
            canvas.save()
            canvas.translate(MARGIN + indent, y)
            layout.draw(canvas)
            canvas.restore()
            val h = layout.height.toFloat() + 4
            y += h
            return h
        }

        for (line in lines) {
            when (line.type) {
                LineType.TITLE -> {
                    drawStaticLayout(line.text, paintTitle)
                    y += 4f
                }
                LineType.SUBTITLE -> {
                    drawStaticLayout(line.text, paintMeta)
                    y += 2f
                }
                LineType.RULE -> {
                    if (needsNewPage(10f)) newPage()
                    canvas.drawLine(MARGIN.toFloat(), y, (PAGE_WIDTH - MARGIN).toFloat(), y, paintRule)
                    y += 10f
                }
                LineType.H1 -> {
                    y += 8f
                    if (needsNewPage(30f)) newPage()
                    drawStaticLayout(line.text, paintH1)
                    y += 2f
                }
                LineType.H2 -> {
                    y += 6f
                    if (needsNewPage(24f)) newPage()
                    drawStaticLayout(line.text, paintH2)
                    y += 2f
                }
                LineType.BULLET -> {
                    drawStaticLayout("- ${line.text}", paintBody, 0f)
                }
                LineType.BODY -> {
                    if (line.text.isBlank()) { y += 6f } else drawStaticLayout(line.text, paintBody)
                }
            }
        }

        finishPage()

        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }

    private data class DocLine(val type: LineType, val text: String)
    private enum class LineType { TITLE, SUBTITLE, RULE, H1, H2, BULLET, BODY }

    private fun parseContent(
        title: String, subtitle: String?, author: String?, content: String
    ): List<DocLine> {
        val result = mutableListOf<DocLine>()
        result += DocLine(LineType.TITLE, title)
        val metaParts = listOfNotNull(subtitle, author?.let { "By $it" })
        if (metaParts.isNotEmpty()) result += DocLine(LineType.SUBTITLE, metaParts.joinToString("  -  "))
        result += DocLine(LineType.RULE, "")

        for (raw in content.lines()) {
            val line = raw.trimEnd()
            when {
                line.startsWith("### ") -> result += DocLine(LineType.H2, line.removePrefix("### ").trim())
                line.startsWith("## ")  -> result += DocLine(LineType.H2, line.removePrefix("## ").trim())
                line.startsWith("# ")   -> result += DocLine(LineType.H1, line.removePrefix("# ").trim())
                line.startsWith("- ")   -> result += DocLine(LineType.BULLET, line.removePrefix("- ").trim())
                line.startsWith("* ")   -> result += DocLine(LineType.BULLET, line.removePrefix("* ").trim())
                line.matches(Regex("\\d+\\. .+")) -> result += DocLine(LineType.BULLET, line.replaceFirst(Regex("^\\d+\\. "), "").trim())
                else -> result += DocLine(LineType.BODY, line)
            }
        }
        return result
    }
}
