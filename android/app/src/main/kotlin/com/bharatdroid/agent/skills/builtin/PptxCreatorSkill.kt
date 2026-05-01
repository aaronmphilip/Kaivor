package com.bharatdroid.agent.skills.builtin

import com.bharatdroid.agent.skills.Skill
import com.bharatdroid.agent.skills.SkillContext
import com.bharatdroid.agent.skills.SkillManifest
import com.bharatdroid.agent.skills.SkillResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PptxCreatorSkill : Skill {

    override val manifest = SkillManifest(
        id = "pptx_creator",
        name = "PowerPoint Creator",
        version = "1.0.0",
        description = "Create a PowerPoint (.pptx) presentation and send it directly in Telegram. Provide a title and slides with headings and bullet points. No API key needed.",
        author = "bharatdroid-team",
        trusted = true,
        permissions = emptySet(),
        exampleParamsHint = """{"title":"Q1 Review","slides":[{"heading":"Overview","points":["Revenue up 20%","New markets entered"]},{"heading":"Next Steps","points":["Hire 5 engineers","Launch v2"]}]} | {"title":"My Plan","content":"# Slide 1\n- Bullet A\n- Bullet B\n# Slide 2\n- Point X"}""",
    )

    override suspend fun execute(context: SkillContext, params: Map<String, Any>): SkillResult {
        val title = (params["title"] as? String)?.trim()
            ?: return SkillResult.Failure("What should the presentation be titled? Provide a 'title'.")

        val slides: List<Slide> = when {
            params["slides"] != null -> parseSlidesParam(params["slides"])
            params["content"] != null || params["text"] != null -> {
                val raw = (params["content"] as? String ?: params["text"] as? String)!!
                parseContentToSlides(raw)
            }
            else -> return SkillResult.Failure("Provide either 'slides' (list with heading + points) or 'content' (# Heading\\n- bullet text).")
        }

        if (slides.isEmpty()) return SkillResult.Failure("No slides found. Use # for slide headings and - for bullet points.")

        return withContext(Dispatchers.Default) {
            try {
                val bytes = buildPptx(title, slides)
                val safeFilename = title.replace(Regex("[^a-zA-Z0-9_\\- ]"), "").trim()
                    .replace(' ', '_').take(40).ifBlank { "presentation" } + ".pptx"
                SkillResult.Media(
                    bytes = bytes,
                    caption = "📊 $title (${slides.size} slides)",
                    mimeType = "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    filename = safeFilename,
                )
            } catch (e: Exception) {
                SkillResult.Failure("Presentation creation failed: ${e.message}")
            }
        }
    }

    data class Slide(val heading: String, val points: List<String>)

    @Suppress("UNCHECKED_CAST")
    private fun parseSlidesParam(raw: Any?): List<Slide> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<String, Any> ?: return@mapNotNull null
            val heading = (map["heading"] as? String ?: map["title"] as? String)?.trim() ?: return@mapNotNull null
            val points = when (val p = map["points"] ?: map["bullets"] ?: map["content"]) {
                is List<*> -> p.mapNotNull { it?.toString()?.trim() }
                is String -> p.lines().map { it.trimStart('-', '*', ' ').trim() }.filter { it.isNotBlank() }
                else -> emptyList()
            }
            Slide(heading, points)
        }
    }

    private fun parseContentToSlides(content: String): List<Slide> {
        val slides = mutableListOf<Slide>()
        var currentHeading: String? = null
        val currentPoints = mutableListOf<String>()

        for (rawLine in content.lines()) {
            val line = rawLine.trim()
            when {
                line.startsWith("# ") || line.startsWith("## ") -> {
                    if (currentHeading != null) slides += Slide(currentHeading, currentPoints.toList())
                    currentHeading = line.trimStart('#', ' ')
                    currentPoints.clear()
                }
                line.startsWith("- ") -> currentPoints += line.removePrefix("- ").trim()
                line.startsWith("* ") -> currentPoints += line.removePrefix("* ").trim()
                line.matches(Regex("\\d+\\. .+")) -> currentPoints += line.replaceFirst(Regex("^\\d+\\. "), "").trim()
                line.isNotBlank() && currentHeading != null -> currentPoints += line
            }
        }
        if (currentHeading != null) slides += Slide(currentHeading, currentPoints.toList())
        return slides
    }

    private fun buildPptx(title: String, slides: List<Slide>): ByteArray {
        val out = ByteArrayOutputStream()
        val zip = ZipOutputStream(out)

        fun addEntry(name: String, content: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.trimIndent().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        // [Content_Types].xml
        val slideTypes = slides.indices.joinToString("\n") { i ->
            """<Override PartName="/ppt/slides/slide${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>"""
        }
        addEntry("[Content_Types].xml", """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
              <Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>
              <Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>
              <Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>
              $slideTypes
            </Types>
        """)

        // _rels/.rels
        addEntry("_rels/.rels", """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
            </Relationships>
        """)

        // ppt/_rels/presentation.xml.rels
        val slideRels = slides.indices.joinToString("\n") { i ->
            """<Relationship Id="rId${i + 3}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide${i + 1}.xml"/>"""
        }
        addEntry("ppt/_rels/presentation.xml.rels", """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="slideMasters/slideMaster1.xml"/>
              <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="theme/theme1.xml"/>
              $slideRels
            </Relationships>
        """)

        // ppt/presentation.xml
        val slideIdList = slides.indices.joinToString("\n") { i ->
            """<p:sldId id="${256 + i}" r:id="rId${i + 3}"/>"""
        }
        addEntry("ppt/presentation.xml", """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                            xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                            xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
              <p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>
              <p:sldIdLst>$slideIdList</p:sldIdLst>
              <p:sldSz cx="9144000" cy="5143500"/>
              <p:notesSz cx="6858000" cy="9144000"/>
            </p:presentation>
        """)

        // ppt/theme/theme1.xml (minimal)
        addEntry("ppt/theme/theme1.xml", """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="BharatTheme">
              <a:themeElements>
                <a:clrScheme name="BharatColors">
                  <a:dk1><a:sysClr lastClr="000000" val="windowText"/></a:dk1>
                  <a:lt1><a:sysClr lastClr="ffffff" val="window"/></a:lt1>
                  <a:dk2><a:srgbClr val="1F3864"/></a:dk2>
                  <a:lt2><a:srgbClr val="E7E6E6"/></a:lt2>
                  <a:accent1><a:srgbClr val="4472C4"/></a:accent1>
                  <a:accent2><a:srgbClr val="ED7D31"/></a:accent2>
                  <a:accent3><a:srgbClr val="A9D18E"/></a:accent3>
                  <a:accent4><a:srgbClr val="FFC000"/></a:accent4>
                  <a:accent5><a:srgbClr val="5A96D0"/></a:accent5>
                  <a:accent6><a:srgbClr val="70AD47"/></a:accent6>
                  <a:hlink><a:srgbClr val="0563C1"/></a:hlink>
                  <a:folHlink><a:srgbClr val="954F72"/></a:folHlink>
                </a:clrScheme>
                <a:fontScheme name="BharatFonts">
                  <a:majorFont><a:latin typeface="Calibri Light"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont>
                  <a:minorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont>
                </a:fontScheme>
                <a:fmtScheme name="Office"/>
              </a:themeElements>
            </a:theme>
        """)

        // slide master rels
        addEntry("ppt/slideMasters/_rels/slideMaster1.xml.rels", """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
              <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme1.xml"/>
            </Relationships>
        """)

        // minimal slide master
        addEntry("ppt/slideMasters/slideMaster1.xml", """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <p:sldMaster xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                         xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                         xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
              <p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>
              <p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>
              <p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst>
            </p:sldMaster>
        """)

        // slide layout rels
        addEntry("ppt/slideLayouts/_rels/slideLayout1.xml.rels", """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="../slideMasters/slideMaster1.xml"/>
            </Relationships>
        """)

        // minimal slide layout
        addEntry("ppt/slideLayouts/slideLayout1.xml", """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <p:sldLayout xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                         xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                         xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" type="blank">
              <p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>
            </p:sldLayout>
        """)

        // individual slides
        slides.forEachIndexed { i, slide ->
            addEntry("ppt/slides/_rels/slide${i + 1}.xml.rels", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
                </Relationships>
            """)
            addEntry("ppt/slides/slide${i + 1}.xml", buildSlideXml(slide, i == 0, title))
        }

        zip.finish()
        return out.toByteArray()
    }

    private fun buildSlideXml(slide: Slide, isTitleSlide: Boolean, presentationTitle: String): String {
        val bgColor = if (isTitleSlide) "1F3864" else "FFFFFF"
        val titleColor = if (isTitleSlide) "FFFFFF" else "1F3864"
        val bulletColor = if (isTitleSlide) "E7E6E6" else "000000"

        val titleY = if (isTitleSlide) "2000000" else "457200"
        val titleH = if (isTitleSlide) "1400000" else "685800"
        val bodyY = if (isTitleSlide) "3600000" else "1600000"

        val escapedHeading = xmlEscape(slide.heading)
        val escapedPTitle = xmlEscape(presentationTitle)

        val subtitleOrBody = if (isTitleSlide) {
            // subtitle = presentation title on title slide — shapeId=3 avoids duplicate with title box (id=2)
            buildTextBox(
                x = "457200", y = bodyY, cx = "8229600", cy = "800000",
                text = escapedPTitle, size = 1800, bold = false, color = "A0C4FF", shapeId = 3
            )
        } else {
            buildBulletBox(slide.points, bodyY, bulletColor)
        }

        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
       xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
       xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
  <p:cSld>
    <p:bg>
      <p:bgPr>
        <a:solidFill><a:srgbClr val="$bgColor"/></a:solidFill>
        <a:effectLst/>
      </p:bgPr>
    </p:bg>
    <p:spTree>
      <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
      <p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>
      ${buildTextBox("457200", titleY, "8229600", titleH, escapedHeading, if (isTitleSlide) 3600 else 2800, true, titleColor, shapeId = 2)}
      $subtitleOrBody
    </p:spTree>
  </p:cSld>
</p:sld>"""
    }

    private fun buildTextBox(
        x: String, y: String, cx: String, cy: String,
        text: String, size: Int, bold: Boolean, color: String,
        shapeId: Int = 2,
    ): String {
        val boldAttr = if (bold) " b=\"1\"" else ""
        return """
      <p:sp>
        <p:nvSpPr><p:cNvPr id="$shapeId" name="shape$shapeId"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr/></p:nvSpPr>
        <p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>
        <p:txBody>
          <a:bodyPr wrap="square" rtlCol="0"><a:normAutofit/></a:bodyPr>
          <a:lstStyle/>
          <a:p><a:r>
            <a:rPr lang="en-US" sz="$size"$boldAttr dirty="0"><a:solidFill><a:srgbClr val="$color"/></a:solidFill></a:rPr>
            <a:t>$text</a:t>
          </a:r></a:p>
        </p:txBody>
      </p:sp>"""
    }

    private fun buildBulletBox(points: List<String>, y: String, color: String): String {
        if (points.isEmpty()) return ""
        val paras = points.joinToString("\n") { point ->
            val escaped = xmlEscape(point)
            """          <a:p>
            <a:pPr marL="342900" indent="-342900"><a:buChar char="•"/></a:pPr>
            <a:r><a:rPr lang="en-US" sz="1800" dirty="0"><a:solidFill><a:srgbClr val="$color"/></a:solidFill></a:rPr><a:t>$escaped</a:t></a:r>
          </a:p>"""
        }
        return """
      <p:sp>
        <p:nvSpPr><p:cNvPr id="3" name="body"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr/></p:nvSpPr>
        <p:spPr><a:xfrm><a:off x="457200" y="$y"/><a:ext cx="8229600" cy="3200000"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>
        <p:txBody>
          <a:bodyPr wrap="square" rtlCol="0"><a:normAutofit/></a:bodyPr>
          <a:lstStyle/>
$paras
        </p:txBody>
      </p:sp>"""
    }

    private fun xmlEscape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
