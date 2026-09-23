package app.yomikku.extension.en.crimsonscrolls

import app.yomikku.lib.paced.Paced
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Crimson Scrolls. Ported from LNReader's crimsonscrolls plugin, reworked for the site's current theme: listings are
 * pages, chapters come from its REST api 100 at a time.
 */
class CrimsonScrolls : HttpSource() {

    override val name = "Crimson Scrolls"
    override val baseUrl = "https://crimsonscrolls.net"
    override val lang = "en"
    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }

    // Listings

    private fun novelsRequest(path: String, page: Int, query: String? = null): Request {
        val url = "$baseUrl/$path".toHttpUrl().newBuilder()
            .addQueryParameter("cs_page", page.toString())
        if (query != null) url.addQueryParameter("s", query)
        return GET(url.build(), headers)
    }

    override fun popularMangaRequest(page: Int) = novelsRequest("novels/trending/overall/", page)

    override fun popularMangaParse(response: Response) = novelsParse(response)

    override fun latestUpdatesRequest(page: Int) = novelsRequest("novels/recently-updated/", page)

    override fun latestUpdatesParse(response: Response) = novelsParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) =
        novelsRequest("novels/", page, query.trim().ifEmpty { null })

    override fun searchMangaParse(response: Response) = novelsParse(response)

    private fun novelsParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val novels = document.select("article.cs-browse-card").mapNotNull { card ->
            val link = card.selectFirst("h2 a[href]") ?: return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                title = link.attr("title").ifBlank { link.text() }.trim()
                thumbnail_url = card.selectFirst("img")?.let { it.absUrl("data-src").ifEmpty { it.absUrl("src") } }
            }
        }
        val page = response.request.url.queryParameter("cs_page")?.toIntOrNull() ?: 1
        val pages = document.selectFirst("[data-browse-pagination][data-total-pages]")?.attr("data-total-pages")
            ?.toIntOrNull() ?: 1
        val hasNext = page < pages
        return MangasPage(novels, hasNext)
    }

    // Details

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            author = document.selectFirst(".cs-novel-creator-card--author a[href*=/user/]:not([aria-label])")?.text()
                ?.trim()
                ?: document.selectFirst(".cs-novel-creator-card--author a[aria-label]")?.attr("aria-label")
                    ?.removePrefix("View ")?.removeSuffix("'s profile")
            genre = document.select(".cs-novel-details__genres a").joinToString { it.text().trim() }.ifEmpty { null }
            description = document.selectFirst(".cs-synopsis-content")?.select("p")
                ?.map { it.text().trim() }?.filter { it.isNotEmpty() }?.joinToString("\n\n")
            val state = document.selectFirst(".cs-novel-hero .cs-cover-status")?.text()?.trim()?.lowercase()
            status = when (state) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                "dropped", "cancelled" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
        }
    }

    // Chapters: the api lists the free chapters, 100 per page.

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val page = client.newCall(GET(baseUrl + manga.url, headers)).awaitSuccess().asJsoup()
        val novelId = page.selectFirst("link[href*=/wp/v2/novel/]")?.attr("href")?.substringAfterLast('/')
            ?: throw Exception("Could not find the novel's id")
        val chapters = mutableListOf<ChapterEntry>()
        var number = 1
        var pages: Int
        do {
            if (number > 1) delay(Paced.PAGE_DELAY_MS)
            val url = "$baseUrl/wp-json/crimsonscrolls/v2/novel-chapters".toHttpUrl().newBuilder()
                .addQueryParameter("novel_id", novelId)
                .addQueryParameter("tier", "free")
                .addQueryParameter("page", number.toString())
                .addQueryParameter("per_page", "100")
                .addQueryParameter("order", "ASC")
                .build()
            val list = Paced.fetch(client, GET(url, headers)).use { json.decodeFromString<ChapterList>(it.body.string()) }
            chapters += list.items
            pages = list.pages
            number++
        } while (number <= pages)
        return chapters.map { chapter ->
            SChapter.create().apply {
                setUrlWithoutDomain(chapter.url)
                name = if (chapter.number.isNullOrEmpty()) chapter.title else "Chapter ${chapter.number}: ${chapter.title}"
                chapter_number = chapter.number?.toFloatOrNull() ?: -1f
                date_upload = chapter.date?.let { runCatching { DATE_FORMAT.parse(it)?.time }.getOrNull() } ?: 0L
            }
        }.reversed()
    }

    // Text

    override fun chapterTextParse(response: Response): String {
        val content = response.asJsoup().selectFirst("article.cs-reader") ?: throw Exception("No chapter text found")
        content.select(
            "header, script, ins, .cs-chapter-ad, .cs-copy-watermark, .cs-reader-end-watermark",
        ).remove()
        return content.html()
    }

    @Serializable
    private class ChapterEntry(val number: String? = null, val title: String = "", val url: String, val date: String? = null)

    @Serializable
    private class ChapterList(val items: List<ChapterEntry> = emptyList(), val pages: Int = 1)

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH)
    }
}
