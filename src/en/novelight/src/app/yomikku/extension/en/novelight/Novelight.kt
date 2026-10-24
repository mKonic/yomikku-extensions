package app.yomikku.extension.en.novelight

import app.yomikku.lib.lnfilters.LnFilters
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Novelight. Ported from LNReader's novelight plugin. The chapter list comes 50 rows a page from an AJAX endpoint, and
 * chapter text from another; both answer only requests that carry `X-Requested-With`.
 */
class Novelight : HttpSource() {

    override val name = "Novelight"
    override val baseUrl = "https://novelight.net"
    override val lang = "en"
    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }

    override fun getFilterList(): FilterList = LnFilters.fromResource(javaClass, "filters.json")

    // Listings

    private fun catalogRequest(page: Int, params: List<Pair<String, String>>): Request {
        val url = "$baseUrl/catalog/".toHttpUrl().newBuilder()
            .apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun popularMangaRequest(page: Int) = catalogRequest(page, listOf("ordering" to "popularity"))

    override fun popularMangaParse(response: Response) = catalogParse(response)

    override fun latestUpdatesRequest(page: Int) = catalogRequest(page, listOf("ordering" to "-time_updated"))

    override fun latestUpdatesParse(response: Response) = catalogParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // filters.json is keyed by the catalog form's own field names.
        val params = LnFilters.queryParams(filters)
        return catalogRequest(page, params + listOfNotNull(query.trim().takeIf { it.isNotEmpty() }?.let { "search" to it }))
    }

    override fun searchMangaParse(response: Response) = catalogParse(response)

    private fun catalogParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val novels = document.select("a.item[href]").map { card ->
            SManga.create().apply {
                url = card.attr("href")
                title = card.selectFirst("div.title")?.text()?.trim().orEmpty()
                thumbnail_url = card.selectFirst("img")?.absUrl("src")?.ifEmpty { null }
            }
        }
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNext = document.selectFirst("a[href*=page=${page + 1}]") != null
        return MangasPage(novels, hasNext)
    }

    // Details

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val info = document.select("div.mini-info > .item").associate { item ->
            item.selectFirst(".sub-header")?.text()?.trim().orEmpty() to item.selectFirst("div.info")
        }
        return SManga.create().apply {
            title = document.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = document.selectFirst(".poster > img")?.absUrl("src")
            description = document.select("section.text-info.section > p").joinToString("\n\n") { it.wholeText().trim() }
            author = info["Author"]?.text()?.trim()?.ifEmpty { null }
            artist = info["Illustrator"]?.text()?.trim()?.ifEmpty { null }
            genre = info["Genres"]?.select("a")?.joinToString { it.text().trim() }
            val release = info["Status"]?.text()?.trim()?.lowercase()
            val translation = info["Translation"]?.text()?.trim()?.lowercase()
            status = when {
                release == "cancelled" || translation == "dropped" -> SManga.CANCELLED
                translation == "paused" -> SManga.ON_HIATUS
                release == "completed" && translation == "completed" -> SManga.COMPLETED
                release == "releasing" || translation == "ongoing" -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
        }
    }

    // Chapters

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val html = client.newCall(GET(baseUrl + manga.url, headers)).awaitSuccess().body.string()
        val bookId = BOOK_ID.find(html)?.groupValues?.get(1) ?: throw Exception("Could not find the book id")
        // One option per page of 50 chapters, newest first; a novel with few chapters has no picker.
        val pages = Jsoup.parse(html).select("#select-pagination-chapter > option").size.coerceAtLeast(1)
        val permits = Semaphore(PARALLEL_PAGES)
        val rows = coroutineScope {
            (1..pages).map { page -> async { permits.withPermit { chapterPage(manga.url, bookId, page) } } }.awaitAll()
        }.flatten()
        return rows.distinctBy { it.url }.also { chapters ->
            chapters.forEachIndexed { index, chapter -> chapter.chapter_number = (chapters.size - index).toFloat() }
        }
    }

    private suspend fun chapterPage(novelUrl: String, bookId: String, page: Int): List<SChapter> {
        val pageUrl = "$baseUrl/book/ajax/chapter-pagination".toHttpUrl().newBuilder()
            .addQueryParameter("book_id", bookId)
            .addQueryParameter("page", page.toString())
            .build()
        val body = client.newCall(GET(pageUrl, ajaxHeaders(novelUrl))).awaitSuccess().body.string()
        val html = json.parseToJsonElement(body).jsonObject["html"]?.jsonPrimitive?.content.orEmpty()
        return Jsoup.parseBodyFragment(html, baseUrl).select("a[href]").map { row ->
            SChapter.create().apply {
                url = row.attr("href")
                val title = row.selectFirst(".title")?.text()?.trim().orEmpty()
                // Paid chapters carry a lock icon in .cost; free ones have no .cost at all.
                name = if (row.selectFirst(".cost") != null) "🔒 $title" else title
                date_upload = row.selectFirst(".date")?.text()?.trim()?.let(::parseDate) ?: 0L
                scanlator = row.selectFirst(".author")?.text()?.trim()?.ifEmpty { null }
            }
        }
    }

    private fun parseDate(text: String) = runCatching { DATE_FORMAT.parse(text)?.time }.getOrNull() ?: 0L

    // Text

    override suspend fun getChapterText(chapter: SChapter): String {
        val page = client.newCall(GET(baseUrl + chapter.url, headers)).awaitSuccess().body.string()
        val chapterId = CHAPTER_ID.find(page)?.groupValues?.get(1) ?: throw Exception("Could not find the chapter id")
        val body = client.newCall(GET("$baseUrl/book/ajax/read-chapter/$chapterId", ajaxHeaders(chapter.url)))
            .awaitSuccess().body.string()
        val reply = json.parseToJsonElement(body).jsonObject
        val content = reply["content"]?.jsonPrimitive?.content.orEmpty()
        val className = reply["class"]?.jsonPrimitive?.content.orEmpty()
        val document = Jsoup.parseBodyFragment(content, baseUrl)
        val text = (if (className.isEmpty()) null else document.selectFirst(".$className")) ?: document.body()
        text.select("script, style, .advertisment").remove()
        return text.html().trim().ifEmpty { throw Exception("This chapter is locked. Unlock it on Novelight to read it.") }
    }

    private fun ajaxHeaders(path: String): Headers = headersBuilder()
        .set("Referer", baseUrl + path)
        .set("X-Requested-With", "XMLHttpRequest")
        .build()

    override fun chapterTextParse(response: Response): String = throw UnsupportedOperationException()

    companion object {
        private const val PARALLEL_PAGES = 6
        private val BOOK_ID = Regex("""const OBJECT_BY_COMMENT = (\d+)""")
        private val CHAPTER_ID = Regex("""const CHAPTER_ID = "(\d+)""")
        private val DATE_FORMAT = SimpleDateFormat("dd.MM.yyyy", Locale.ENGLISH)
    }
}
