package app.yomikku.extension.en.dragonholic

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
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
import org.jsoup.parser.Parser
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Dragonholic, now at dragonholictranslations.com on its own theme. Ported from LNReader's dragonholic plugin; the
 * listings use the site's series catalogue rather than the home page.
 */
class Dragonholic : HttpSource() {

    override val name = "Dragonholic"
    override val baseUrl = "https://dragonholictranslations.com"
    override val lang = "en"
    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    private val apiHeaders by lazy {
        headers.newBuilder().add("Accept", "application/json").add("X-Requested-With", "XMLHttpRequest").build()
    }

    // Listings: the series catalogue, 12 a page.

    private fun seriesRequest(page: Int, sort: String) =
        GET("$baseUrl/series/page/$page/?sort=$sort&order=desc", headers)

    override fun popularMangaRequest(page: Int) = seriesRequest(page, "trending")

    override fun popularMangaParse(response: Response) = seriesParse(response)

    override fun latestUpdatesRequest(page: Int) = seriesRequest(page, "updated")

    override fun latestUpdatesParse(response: Response) = seriesParse(response)

    private fun seriesParse(response: Response): MangasPage {
        val novels = response.asJsoup().select("a[href^=/series/]:has(img)").mapNotNull { card ->
            val slug = SERIES_SLUG.find(card.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
            SManga.create().apply {
                url = "/series/$slug/"
                title = card.selectFirst("h3")?.text()?.clean() ?: card.selectFirst("img")?.attr("alt")?.clean().orEmpty()
                thumbnail_url = card.selectFirst("img")?.absUrl("src")
            }
        }.filter { it.title.isNotEmpty() }.distinctBy { it.url }
        return MangasPage(novels, novels.size >= SERIES_PAGE)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/api/search".toHttpUrl().newBuilder().addQueryParameter("q", query.trim()).build()
        return GET(url, apiHeaders)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val novels = json.decodeFromString<SearchResults>(response.body.string()).results.mapNotNull { result ->
            val slug = SERIES_SLUG.find(result.url)?.groupValues?.get(1) ?: return@mapNotNull null
            SManga.create().apply {
                url = "/series/$slug/"
                title = Parser.unescapeEntities(result.title, false).clean()
                thumbnail_url = result.thumbnail
            }
        }
        return MangasPage(novels, false)
    }

    // Details

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h1")?.text()?.clean().orEmpty()
            thumbnail_url = document.selectFirst("[x-data=coverModal()] img")?.absUrl("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            author = document.selectFirst("a[href*=/author/]")?.text()?.clean()?.ifEmpty { null }
            genre = document.select("a[href*=/genre/]").map { it.text().clean() }.filter { it.isNotEmpty() }
                .distinct().joinToString().ifEmpty { null }
            description = document.select("[x-ref=synopsis] p").map { it.text().clean() }
                .filter { it.isNotEmpty() && !SYNOPSIS_LABEL.matches(it) }
                .joinToString("\n\n").ifEmpty { null }
            val state = document.select("div[class*=rounded-full]").map { it.text().lowercase() }
                .firstOrNull { STATUS.containsMatchIn(it) }.orEmpty()
            status = when {
                "ongoing" in state -> SManga.ONGOING
                "completed" in state -> SManga.COMPLETED
                "hiatus" in state -> SManga.ON_HIATUS
                "cancelled" in state || "dropped" in state -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
        }
    }

    // Chapters: the page names the series id, and the api lists every chapter at once.

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val page = client.newCall(GET(baseUrl + manga.url, headers)).awaitSuccess().use { it.body.string() }
        val seriesId = SERIES_ID.find(page)?.groupValues?.get(1) ?: throw Exception("Could not find the series id")
        val listUrl = "$baseUrl/api/chapters?series_id=$seriesId&load_all=1&sort_order=asc"
        val list = client.newCall(GET(listUrl, apiHeaders)).awaitSuccess()
            .use { json.decodeFromString<ChapterList>(it.body.string()) }
        val slug = SERIES_SLUG.find(manga.url)?.groupValues?.get(1) ?: throw Exception("Invalid novel path")
        return (list.data?.chapters ?: list.chapters).filter { it.slug.isNotEmpty() }.mapIndexed { index, chapter ->
            val title = listOfNotNull(chapter.heading ?: chapter.name, chapter.subtitle)
                .map { Parser.unescapeEntities(it, false).clean() }.filter { it.isNotEmpty() }
                .joinToString(" - ").ifEmpty { chapter.slug }
            SChapter.create().apply {
                url = "/series/$slug/${chapter.slug}/"
                name = (if (chapter.is_premium) "🔒 " else "") + title
                chapter_number = chapter.chapter_order?.toFloatOrNull()?.takeIf { it > 0 } ?: (index + 1f)
                date_upload = chapter.created_at?.let { runCatching { DATE_FORMAT.parse(it)?.time }.getOrNull() } ?: 0L
            }
        }.reversed()
    }

    // Text

    override fun chapterTextParse(response: Response): String {
        val content = response.asJsoup().selectFirst(".chapter-content") ?: throw Exception("No chapter text found")
        content.select("script, style, ins, .ad-container, [data-lumina-ad-code]").remove()
        val paragraphs = content.select("p").filter { it.hasText() || it.selectFirst("img") != null }
        if (paragraphs.isEmpty()) throw Exception("This chapter is locked or empty")
        return paragraphs.joinToString("") { "<p>${it.html().replace(ZERO_WIDTH, "").trim()}</p>" }
    }

    /** The site salts its text with zero-width spaces. */
    private fun String.clean() = replace(ZERO_WIDTH, "").trim()

    @Serializable
    private class SearchResult(val title: String = "", val url: String = "", val thumbnail: String? = null)

    @Serializable
    private class SearchResults(val results: List<SearchResult> = emptyList())

    @Suppress("PropertyName")
    @Serializable
    private class Chapter(
        val name: String? = null,
        val slug: String = "",
        val heading: String? = null,
        val subtitle: String? = null,
        val chapter_order: String? = null,
        val created_at: String? = null,
        val is_premium: Boolean = false,
    )

    @Serializable
    private class ChapterData(val chapters: List<Chapter> = emptyList())

    @Serializable
    private class ChapterList(val data: ChapterData? = null, val chapters: List<Chapter> = emptyList())

    companion object {
        private const val SERIES_PAGE = 12
        private val SERIES_SLUG = Regex("""/series/([^/?#]+)""")
        private val SERIES_ID = Regex("""seriesId:\s*(\d+)""")
        private val SYNOPSIS_LABEL = Regex("""(?i)synopsis:?""")
        private val STATUS = Regex("""ongoing|completed|hiatus|cancelled|dropped""")
        private val ZERO_WIDTH = Regex("[​‌‍﻿]")
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
    }
}
