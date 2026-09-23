package app.yomikku.extension.en.novelhi

import app.yomikku.lib.lnfilters.LnFilters
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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * NovelHi. Ported from LNReader's novelhi plugin, updated for the site's current pages: listings and chapters come
 * from its JSON api, and chapter text from an api call the chapter page hands a token for, sent ROT13-encoded.
 */
class NovelHi : HttpSource() {

    override val name = "NovelHi"
    override val baseUrl = "https://novelhi.com"
    override val lang = "en"
    override val supportsLatest = false

    private val json = Json { ignoreUnknownKeys = true }

    override fun getFilterList(): FilterList = LnFilters.fromResource(javaClass, "filters.json")

    // Listings

    override fun popularMangaRequest(page: Int) = searchMangaRequest(page, "", FilterList())

    override fun popularMangaParse(response: Response) = searchMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val params = LnFilters.queryParams(filters).toMap()
        val url = "$baseUrl/book/searchByPageInShelf".toHttpUrl().newBuilder()
            .addQueryParameter("curr", page.toString())
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .apply {
                if (query.isNotBlank()) addQueryParameter("keyword", query.trim())
                params["genres"]?.let { addQueryParameter("bookGenres[]", it) }
                params["order"]?.let { addQueryParameter("bookStatus", it) }
                params["time"]?.let { addQueryParameter("updatePeriod", it) }
            }
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val page = json.decodeFromString<Listing>(response.body.string()).data
        val novels = page.list.map { book ->
            SManga.create().apply {
                // The short path redirects to the novel's current page, wherever the site has moved it.
                url = "/s/${book.simpleName}"
                title = book.bookName
                thumbnail_url = book.picUrl
                author = book.authorName
                description = book.bookDesc?.let(::summary)
                genre = book.genres.joinToString { it.genreName }.ifEmpty { null }
                status = if (book.bookStatus == "1") SManga.COMPLETED else SManga.ONGOING
            }
        }
        val current = page.pageNum.toIntOrNull() ?: 1
        val size = page.pageSize.toIntOrNull() ?: PAGE_SIZE
        val total = page.total.toIntOrNull() ?: 0
        return MangasPage(novels, current * size < total)
    }

    // Details

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val document = client.newCall(mangaDetailsRequest(manga)).awaitSuccess().use { it.asJsoup() }
        val bookId = document.bookId()
        val genres = bookId?.let { id ->
            client.newCall(GET("$baseUrl/book/queryBookGenre?bookId=$id", headers)).awaitSuccess()
                .use { json.decodeFromString<Genres>(it.body.string()).data }
        }.orEmpty()
        return SManga.create().apply {
            title = document.selectFirst("#bookNamedHidden")?.attr("value")?.ifBlank { null }
                ?: document.selectFirst("h1")?.text().orEmpty()
            author = document.selectFirst("#authorName")?.attr("value")?.ifBlank { null }
            thumbnail_url = document.selectFirst("img.decorate-img, img.cover")?.absUrl("src")
            description = document.selectFirst(".mobile-detail-desc, .intro_txt p")?.html()?.let(::summary)
            genre = genres.joinToString { it.genreName }.ifEmpty { null }
            val statusText = document.select("span").firstOrNull { it.text().startsWith("Status") }?.text().orEmpty()
            status = when {
                "Completed" in statusText -> SManga.COMPLETED
                "Ongoing" in statusText -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
        }
    }

    private fun Document.bookId() = selectFirst("#bookId")?.attr("value")?.ifBlank { null }

    private fun summary(html: String) = Jsoup.parseBodyFragment(html.replace(BR, "\n")).body().wholeText().trim()

    // Chapters: the api lists them newest first.

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val document = client.newCall(mangaDetailsRequest(manga)).awaitSuccess().use { it.asJsoup() }
        if (document.selectFirst("#translate") != null) {
            throw Exception("This novel has been removed and is no longer available")
        }
        val bookId = document.bookId() ?: throw Exception("Could not find the novel's id")
        val path = document.selectFirst("#canonicalNovelPath")?.attr("value")?.ifBlank { null }
            ?: document.location().toHttpUrl().encodedPath
        val url = "$baseUrl/book/queryIndexList?bookId=$bookId&curr=1&limit=$ALL_CHAPTERS"
        val chapters = client.newCall(GET(url, headers)).awaitSuccess()
            .use { json.decodeFromString<Chapters>(it.body.string()).data?.list }
            .orEmpty()
        return chapters.map { chapter ->
            SChapter.create().apply {
                this.url = "$path/${chapter.indexNum}"
                name = chapter.indexName
                chapter_number = chapter.indexNum.toFloatOrNull() ?: -1f
                date_upload = chapter.createTime?.let { runCatching { DATE_FORMAT.parse(it.take(19))?.time }.getOrNull() } ?: 0L
            }
        }
    }

    // Text: the page carries a token for the api call that returns the text.

    override suspend fun getChapterText(chapter: SChapter): String {
        val pageUrl = baseUrl + chapter.url
        val document = client.newCall(GET(pageUrl, headers)).awaitSuccess().use { it.asJsoup() }
        val path = document.selectFirst("#chapterContentPath")?.attr("value")
        val token = document.selectFirst("#chapterContentToken")?.attr("value")
        if (path.isNullOrBlank() || token.isNullOrBlank()) {
            return document.selectFirst("#showReading")?.apply { select("script, ins").remove() }?.html()
                ?: throw Exception("No chapter text found")
        }
        val contentUrl = (baseUrl + path).toHttpUrl().newBuilder().addQueryParameter("token", token).build()
        val contentHeaders = headers.newBuilder()
            .set("Referer", pageUrl)
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
        val content = client.newCall(GET(contentUrl, contentHeaders)).awaitSuccess()
            .use { json.decodeFromString<Content>(it.body.string()).data?.content }
            ?: throw Exception("No chapter text found")
        return rot13(content)
            .replace(SENT_OPEN, "<p")
            .replace(SENT_CLOSE, "</p>")
            .replace(BR, "")
    }

    /** Rotates letters by 13 outside of tags; the site's own font draws them back the right way. */
    private fun rot13(html: String) = TAG_OR_LETTER.replace(html) { match ->
        val c = match.value.singleOrNull()
        if (c == null || !c.isLetter()) {
            match.value
        } else {
            val base = if (c <= 'Z') 'A' else 'a'
            ((c - base + 13) % 26 + base.code).toChar().toString()
        }
    }

    override fun getChapterUrl(chapter: SChapter) = baseUrl + chapter.url

    @Serializable
    private class Listing(val data: ListingPage)

    @Serializable
    private class ListingPage(
        val pageNum: String = "1",
        val pageSize: String = "0",
        val total: String = "0",
        val list: List<Book> = emptyList(),
    )

    @Serializable
    private class Genre(val genreName: String)

    @Serializable
    private class Book(
        val bookName: String,
        val simpleName: String,
        val picUrl: String? = null,
        val authorName: String? = null,
        val bookDesc: String? = null,
        val bookStatus: String? = null,
        val genres: List<Genre> = emptyList(),
    )

    @Serializable
    private class Genres(val data: List<Genre>? = null)

    @Serializable
    private class Chapters(val data: ChapterPage? = null)

    @Serializable
    private class ChapterPage(val list: List<Chapter> = emptyList())

    @Serializable
    private class Chapter(val indexNum: String, val indexName: String, val createTime: String? = null)

    @Serializable
    private class Content(val data: ContentData? = null)

    @Serializable
    private class ContentData(val content: String? = null)

    companion object {
        private const val PAGE_SIZE = 20
        private const val ALL_CHAPTERS = 100000
        private val BR = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
        private val SENT_OPEN = Regex("""<sent\b""", RegexOption.IGNORE_CASE)
        private val SENT_CLOSE = Regex("""</sent>""", RegexOption.IGNORE_CASE)
        private val TAG_OR_LETTER = Regex("""<[^>]+>|[a-zA-Z]""")
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
