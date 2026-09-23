package app.yomikku.extension.en.indratranslations

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Indra Translations. Ported from LNReader's indraTranslations plugin, rewritten for the site's own theme. The series
 * page lists the whole catalogue.
 */
class IndraTranslations : HttpSource() {

    override val name = "Indra Translations"
    override val baseUrl = "https://indratranslations.com"
    override val lang = "en"
    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }

    // Listings

    private fun seriesRequest(orderBy: String, keyword: String? = null): Request {
        val url = "$baseUrl/series/".toHttpUrl().newBuilder().addQueryParameter("orderby", orderBy)
        if (keyword != null) url.addQueryParameter("keyword", keyword)
        return GET(url.build(), headers)
    }

    override fun popularMangaRequest(page: Int) = seriesRequest("views")

    override fun popularMangaParse(response: Response) = novelsParse(response)

    override fun latestUpdatesRequest(page: Int) = seriesRequest("update")

    override fun latestUpdatesParse(response: Response) = novelsParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) =
        seriesRequest("views", query.trim().ifEmpty { null })

    override fun searchMangaParse(response: Response) = novelsParse(response)

    private fun novelsParse(response: Response): MangasPage {
        // Cards link through their onclick handler.
        val novels = response.asJsoup().select(".series-card").mapNotNull { card ->
            val href = CARD_LINK.find(card.attr("onclick"))?.groupValues?.get(1) ?: return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(href)
                title = card.selectFirst(".series-card-title")?.attr("title")?.trim().orEmpty()
                thumbnail_url = card.selectFirst("img")?.absUrl("src")
                genre = card.selectFirst(".series-card-genres")?.text()?.trim()
            }
        }
        return MangasPage(novels, false)
    }

    // Details

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            description = document.selectFirst(".synopsis-wrapper")?.let { synopsis ->
                synopsis.select(".synopsis-overlay, button").remove()
                synopsis.select("p").map { it.text().trim() }.filter { it.isNotEmpty() }
                    .ifEmpty { listOf(synopsis.text().trim()) }.joinToString("\n\n")
            }
            val info = document.select("li, span, div").map { it.ownText().trim() }
            author = info.firstOrNull { it.startsWith("Author:") }?.substringAfter(':')?.trim()?.ifEmpty { null }
        }
    }

    // Chapters

    override fun chapterListParse(response: Response): List<SChapter> {
        // The page shows 100 chapters and keeps the whole list in a script for the rest.
        val script = response.body.string()
        val chapters = CHAPTERS.find(script)?.groupValues?.get(1)?.let { json.decodeFromString<List<Chapter>>(it) }
            ?: throw Exception("No chapters found")
        return chapters.map { chapter ->
            val locked = chapter.vip != 0 || chapter.price > 0
            SChapter.create().apply {
                setUrlWithoutDomain(chapter.link)
                name = (if (locked) "🔒 " else "") + chapter.title
                chapter_number = chapter.num
                date_upload = runCatching { DATE_FORMAT.parse(chapter.date)?.time }.getOrNull() ?: 0L
            }
        }.sortedByDescending { it.chapter_number }
    }

    // Text

    override fun chapterTextParse(response: Response): String {
        val content = response.asJsoup().selectFirst(".td-reading-flow") ?: throw Exception("No chapter text found. Paid chapters need coins on the site.")
        // Invisible attribution lines and marks the site puts between paragraphs; a browser never shows them.
        content.select(".td-s-para-noise, .td-s-noise, .td-hidden-watermark, .td-canary-trap, script").remove()
        return content.html()
    }

    @Serializable
    private class Chapter(
        val num: Float = -1f,
        val vip: Int = 0,
        val price: Int = 0,
        val title: String,
        val date: String = "",
        val link: String,
    )

    companion object {
        private val CARD_LINK = Regex("""location\.href='([^']+)'""")
        private val CHAPTERS = Regex("""var TD_Story_Chapters = (\[.*?]);""")
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
    }
}
