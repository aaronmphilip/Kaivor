package com.bharatdroid.agent

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String,
)

data class ResearchSource(
    val title: String,
    val url: String,
    val snippet: String,
    val extract: String,
    val domain: String,
)

data class ResearchPacket(
    val query: String,
    val sources: List<ResearchSource>,
)

class InternetResearcher {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun research(
        query: String,
        resultLimit: Int = 5,
        pageFetchLimit: Int = 3,
    ): ResearchPacket {
        val searchResults = searchDuckDuckGo(query, resultLimit)
        val enriched = searchResults.take(pageFetchLimit).map { result ->
            result.toResearchSource(fetchPageExtract(result.url))
        } + searchResults.drop(pageFetchLimit).map { result ->
            result.toResearchSource("")
        }
        return ResearchPacket(query = query, sources = enriched)
    }

    private suspend fun searchDuckDuckGo(query: String, resultLimit: Int): List<SearchResult> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val html = getText("https://html.duckduckgo.com/html/?q=$encoded") ?: ""
        val document = Jsoup.parse(html)

        val parsed = document.select(".result, .web-result").mapNotNull { block ->
            val anchor = block.selectFirst("a.result__a, a[data-testid=result-title-a], .result__title a")
                ?: return@mapNotNull null
            val url = decodeDuckDuckGoUrl(anchor.attr("href")) ?: return@mapNotNull null
            if (!url.startsWith("http")) return@mapNotNull null
            SearchResult(
                title = anchor.text().cleanForPrompt(180),
                url = url,
                snippet = (
                    block.selectFirst(".result__snippet, .result-snippet, .result__extras__url")
                        ?.text()
                        .orEmpty()
                    ).cleanForPrompt(280),
            )
        }.distinctBy { it.url }.take(resultLimit)

        if (parsed.isNotEmpty()) return parsed
        return fallbackInstantAnswer(query).take(resultLimit)
    }

    private suspend fun fallbackInstantAnswer(query: String): List<SearchResult> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val body = getText("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1")
            ?: return emptyList()

        return try {
            val json = JsonParser.parseString(body).asJsonObject
            val abstractText = json.get("AbstractText")?.asString.orEmpty().cleanForPrompt(280)
            val abstractUrl = json.get("AbstractURL")?.asString.orEmpty()
            val heading = json.get("Heading")?.asString.orEmpty()
            if (abstractText.isBlank() || abstractUrl.isBlank()) emptyList()
            else listOf(
                SearchResult(
                    title = heading.ifBlank { query },
                    url = abstractUrl,
                    snippet = abstractText,
                )
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchPageExtract(url: String): String {
        val html = getText(url) ?: return ""
        return try {
            val document = Jsoup.parse(html)
            document.select("script, style, noscript, nav, footer, header, form, svg").remove()

            val paragraphText = document.select("article p, main p, p")
                .map { it.text().cleanForPrompt(500) }
                .filter { it.length > 40 }
                .take(8)

            if (paragraphText.isNotEmpty()) {
                paragraphText.joinToString(" ").cleanForPrompt(1800)
            } else {
                document.body()?.text().orEmpty().cleanForPrompt(1800)
            }
        } catch (_: Exception) {
            ""
        }
    }

    private suspend fun getText(url: String): String? = withContext(Dispatchers.IO) {
        try {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0 Mobile Safari/537.36"
                    )
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val contentType = response.header("Content-Type").orEmpty()
                if (contentType.contains("application/pdf", ignoreCase = true)) return@use null
                response.body?.string()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeDuckDuckGoUrl(rawHref: String): String? {
        if (rawHref.isBlank()) return null
        if (rawHref.startsWith("http")) return rawHref
        val prefixed = if (rawHref.startsWith("//")) "https:$rawHref" else "https://html.duckduckgo.com$rawHref"
        val httpUrl = prefixed.toHttpUrlOrNull() ?: return rawHref
        val encoded = httpUrl.queryParameter("uddg") ?: return rawHref
        return try {
            URLDecoder.decode(encoded, Charsets.UTF_8.name())
        } catch (_: Exception) {
            rawHref
        }
    }

    private fun SearchResult.toResearchSource(extract: String): ResearchSource =
        ResearchSource(
            title = title,
            url = url,
            snippet = snippet,
            extract = extract,
            domain = try {
                URI(url).host?.removePrefix("www.").orEmpty()
            } catch (_: Exception) {
                ""
            }
        )

    private fun String.cleanForPrompt(maxLength: Int): String =
        replace(Regex("\\s+"), " ").trim().take(maxLength)
}
