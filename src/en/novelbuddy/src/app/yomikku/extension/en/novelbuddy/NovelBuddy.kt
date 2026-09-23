package app.yomikku.extension.en.novelbuddy

import app.yomikku.lib.lnfilters.LnFilters
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * NovelBuddy. Ported from LNReader's novelbuddy plugin: listings, chapters and text come from the site's JSON api,
 * the details from the data the novel's page embeds for its own scripts.
 */
class NovelBuddy : HttpSource() {

    override val name = "NovelBuddy"
    override val baseUrl = "https://novelbuddy.me"
    override val lang = "en"
    override val supportsLatest = true

    private val api = "https://api.novelbuddy.me"

    private val json = Json { ignoreUnknownKeys = true }

    /** Freewebnovel's watermark, spelled in look-alike letters so a plain search misses it. */
    private val watermark by lazy {
        Regex(javaClass.getResourceAsStream("/watermark.regex")!!.bufferedReader().use { it.readText().trim() })
    }

    override fun getFilterList(): FilterList = LnFilters.fromResource(javaClass, "filters.json")

    // Listings

    private fun searchUrl(page: Int, build: HttpUrl.Builder.() -> Unit) = "$api/titles/search".toHttpUrl().newBuilder()
        .addQueryParameter("page", page.toString())
        .addQueryParameter("limit", PAGE_SIZE.toString())
        .apply(build)
        .build()

    override fun popularMangaRequest(page: Int) = GET(searchUrl(page) { addQueryParameter("sort", "views") }, headers)

    override fun popularMangaParse(response: Response) = novelsParse(response)

    override fun latestUpdatesRequest(page: Int) = GET(searchUrl(page) { addQueryParameter("sort", "latest") }, headers)

    override fun latestUpdatesParse(response: Response) = novelsParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val params = LnFilters.queryParams(filters).groupBy({ it.first }, { it.second })
        val url = searchUrl(page) {
            if (query.isNotBlank()) addQueryParameter("q", query.trim())
            params["orderBy"]?.firstOrNull()?.let { addQueryParameter("sort", it) }
            params["status"]?.firstOrNull()?.takeIf { it != "all" }?.let { addQueryParameter("status", it) }
            params["min_ch"]?.firstOrNull()?.chapterCount()?.let { addQueryParameter("min_ch", it) }
            params["max_ch"]?.firstOrNull()?.chapterCount()?.let { addQueryParameter("max_ch", it) }
            params["demo"]?.takeIf { it.isNotEmpty() }?.let { addQueryParameter("demographic", it.joinToString(",")) }
            filters.filterIsInstance<LnFilters.ExcludableGroup>().forEach { group ->
                group.included.takeIf { it.isNotEmpty() }?.let { addQueryParameter("genres", it.joinToString(",")) }
                group.excluded.takeIf { it.isNotEmpty() }?.let { addQueryParameter("exclude", it.joinToString(",")) }
            }
        }
        return GET(url, headers)
    }

    /** The site takes chapter counts from 0 to 10000 and rejects anything else. */
    private fun String.chapterCount() = trim().toIntOrNull()?.takeIf { it in 0..10000 }?.toString()

    override fun searchMangaParse(response: Response) = novelsParse(response)

    private fun novelsParse(response: Response): MangasPage {
        val items = json.decodeFromString<SearchResult>(response.body.string()).data.items
        val novels = items.map { item ->
            SManga.create().apply {
                url = "/" + item.url.trimStart('/')
                title = item.name
                thumbnail_url = item.cover
            }
        }
        return MangasPage(novels, items.size == PAGE_SIZE)
    }

    // Details

    override fun mangaDetailsParse(response: Response): SManga {
        val novel = pageData(response.body.string()).props.pageProps.initialManga ?: throw Exception("Novel not found")
        return SManga.create().apply {
            title = novel.name ?: "Untitled"
            thumbnail_url = novel.cover
            author = novel.authors.joinToString { it.name }.ifEmpty { null }
            artist = novel.artists.joinToString { it.name }.ifEmpty { null }
            genre = novel.genres.joinToString { it.name }.ifEmpty { null }
            description = novel.summary?.let { summary ->
                val body = Jsoup.parseBodyFragment(summary).body()
                body.select("br").forEach { it.after("\n") }
                body.select("p").forEach { it.appendText("\n\n") }
                body.wholeText().lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
            }
            status = when (novel.status?.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                "dropped", "cancelled" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
        }
    }

    private fun pageData(html: String): PageData {
        val data = NEXT_DATA.find(html)?.groupValues?.get(1) ?: throw Exception("Could not find the page's data")
        return json.decodeFromString(data)
    }

    // Chapters: the api lists them newest first; each keeps the ids the text api needs.

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val page = client.newCall(mangaDetailsRequest(manga)).awaitSuccess().use { it.body.string() }
        val novel = pageData(page).props.pageProps.initialManga ?: throw Exception("Novel not found")
        val version = novel.content_version ?: novel.cv
        val url = "$api/titles/${novel.id}/chapters" + (version?.let { "?cv=$it" } ?: "")
        val chapters = client.newCall(GET(url, headers)).awaitSuccess().use {
            json.decodeFromString<ChapterList>(it.body.string()).data?.chapters
        }
        return (chapters ?: novel.chapters).map { chapter ->
            SChapter.create().apply {
                this.url = "/" + chapter.url.trimStart('/') + "?id=${novel.id}&chapterId=${chapter.id}"
                name = chapter.name
                chapter_number = chapter.number ?: -1f
                date_upload = (chapter.updated_at ?: chapter.updatedAt)
                    ?.let { runCatching { DATE_FORMAT.parse(it.take(19))?.time }.getOrNull() } ?: 0L
            }
        }
    }

    // Text

    override fun chapterTextRequest(chapter: SChapter): Request {
        val url = (baseUrl + chapter.url).toHttpUrl()
        val novelId = url.queryParameter("id")
        val chapterId = url.queryParameter("chapterId")
        return if (novelId != null && chapterId != null) {
            GET("$api/titles/$novelId/chapters/$chapterId", headers)
        } else {
            GET(baseUrl + chapter.url, headers)
        }
    }

    override fun getChapterUrl(chapter: SChapter) = baseUrl + chapter.url.substringBefore('?')

    override fun chapterTextParse(response: Response): String {
        val body = response.body.string()
        val content = if (response.request.url.host.startsWith("api.")) {
            json.decodeFromString<ChapterText>(body).data?.chapter?.content
        } else {
            json.decodeFromString<ChapterPage>(NEXT_DATA.find(body)?.groupValues?.get(1) ?: "{}")
                .props?.pageProps?.initialChapter?.content
        }
        if (content.isNullOrBlank()) throw Exception("No chapter text found")
        return watermark.replace(WEBNOVEL_NOTICE.replace(content, ""), "")
    }

    @Serializable
    private class SearchResult(val data: Items = Items())

    @Serializable
    private class Items(val items: List<Item> = emptyList())

    @Serializable
    private class Item(val url: String, val name: String, val cover: String? = null)

    @Serializable
    private class PageData(val props: Props)

    @Serializable
    private class Props(val pageProps: PageProps)

    @Serializable
    private class PageProps(val initialManga: Novel? = null)

    @Serializable
    private class Named(val name: String)

    @Suppress("PropertyName")
    @Serializable
    private class Novel(
        val id: String,
        val name: String? = null,
        val cover: String? = null,
        val status: String? = null,
        val summary: String? = null,
        val authors: List<Named> = emptyList(),
        val artists: List<Named> = emptyList(),
        val genres: List<Named> = emptyList(),
        val chapters: List<Chapter> = emptyList(),
        val cv: Long? = null,
        val content_version: Long? = null,
    )

    @Suppress("PropertyName")
    @Serializable
    private class Chapter(
        val id: String,
        val url: String,
        val name: String,
        val number: Float? = null,
        val updated_at: String? = null,
        val updatedAt: String? = null,
    )

    @Serializable
    private class ChapterList(val data: ChapterListData? = null)

    @Serializable
    private class ChapterListData(val chapters: List<Chapter>? = null)

    @Serializable
    private class ChapterText(val data: ChapterTextData? = null)

    @Serializable
    private class ChapterTextData(val chapter: Content? = null)

    @Serializable
    private class Content(val content: String? = null)

    @Serializable
    private class ChapterPage(val props: ChapterProps? = null)

    @Serializable
    private class ChapterProps(val pageProps: ChapterPageProps? = null)

    @Serializable
    private class ChapterPageProps(val initialChapter: Content? = null)

    companion object {
        private const val PAGE_SIZE = 24
        private val NEXT_DATA = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        private val WEBNOVEL_NOTICE = Regex(
            """Find authorized novels in Webnovel.*?faster updates, better experience.*?Please click www\.webnovel\.com for visiting\.""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
