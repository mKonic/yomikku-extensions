package app.yomikku.extension.en.novelrest

import app.yomikku.lib.lnfilters.LnFilters
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** NovelRest. Ported from LNReader's novelrest plugin; the site has an api made for readers. */
class NovelRest : HttpSource() {

    override val name = "NovelRest"
    override val baseUrl = "https://novelrest.vercel.app"
    override val lang = "en"
    override val supportsLatest = true

    private val api = "$baseUrl/api/lnreader"
    private val json = Json { ignoreUnknownKeys = true }

    override fun getFilterList(): FilterList = LnFilters.fromResource(javaClass, "filters.json")

    // Listings

    private fun novelsRequest(page: Int, params: Map<String, String>): Request {
        val url = "$api/novels".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "20")
        params.filterValues { it.isNotEmpty() }.forEach { (key, value) -> url.addQueryParameter(key, value) }
        return GET(url.build(), headers)
    }

    override fun popularMangaRequest(page: Int) = novelsRequest(page, mapOf("sort" to "popular"))

    override fun popularMangaParse(response: Response) = novelsParse(response)

    override fun latestUpdatesRequest(page: Int) = novelsRequest(page, mapOf("sort" to "latest"))

    override fun latestUpdatesParse(response: Response) = novelsParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) =
        novelsRequest(page, LnFilters.queryParams(filters).toMap() + ("q" to query.trim()))

    override fun searchMangaParse(response: Response) = novelsParse(response)

    private fun novelsParse(response: Response): MangasPage {
        val list = json.decodeFromString<NovelList>(response.body.string())
        val novels = list.novels.map { novel ->
            SManga.create().apply {
                url = "/novels/${novel.slug}"
                title = novel.title
                thumbnail_url = novel.coverImage
            }
        }
        return MangasPage(novels, list.pagination?.hasMore == true)
    }

    // Details

    private fun slugOf(url: String) = url.removePrefix("/novels/").substringBefore('/')

    override fun getMangaUrl(manga: SManga) = baseUrl + manga.url

    override fun mangaDetailsRequest(manga: SManga) = GET("$api/novels/${slugOf(manga.url)}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val novel = json.decodeFromString<NovelDetails>(response.body.string())
        return SManga.create().apply {
            title = novel.title
            author = novel.author
            thumbnail_url = novel.coverImage
            description = novel.description
            genre = novel.genres.map(::name).joinToString().ifEmpty { null }
            status = when (novel.status) {
                "ONGOING" -> SManga.ONGOING
                "COMPLETED" -> SManga.COMPLETED
                "HIATUS" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }

    /** Genres come either as names or as `{"name": ...}`. */
    private fun name(element: JsonElement) =
        (element as? JsonPrimitive)?.content ?: (element as JsonObject)["name"]!!.jsonPrimitive.content

    // Chapters

    override fun chapterListRequest(manga: SManga) = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val slug = response.request.url.pathSegments.last()
        return json.decodeFromString<NovelDetails>(response.body.string()).chapters.map { chapter ->
            SChapter.create().apply {
                url = "/novels/$slug/chapters/${chapter.number}"
                name = chapter.title ?: "Chapter ${chapter.number}"
                chapter_number = chapter.number.toFloat()
                date_upload = chapter.createdAt?.let { runCatching { DATE_FORMAT.parse(it.take(19))?.time }.getOrNull() } ?: 0L
            }
        }.reversed()
    }

    // Text

    override fun getChapterUrl(chapter: SChapter) = baseUrl + chapter.url

    override fun chapterTextRequest(chapter: SChapter) = GET(api + chapter.url, headers)

    override fun chapterTextParse(response: Response): String =
        json.decodeFromString<ChapterText>(response.body.string()).contentHtml
            ?: throw Exception("No chapter text found")

    @Serializable
    private class Pagination(val hasMore: Boolean = false)

    @Serializable
    private class NovelEntry(val slug: String, val title: String, val coverImage: String? = null)

    @Serializable
    private class NovelList(val novels: List<NovelEntry> = emptyList(), val pagination: Pagination? = null)

    @Serializable
    private class ChapterEntry(val number: Int, val title: String? = null, val createdAt: String? = null)

    @Serializable
    private class NovelDetails(
        val title: String,
        val author: String? = null,
        val description: String? = null,
        val coverImage: String? = null,
        val status: String? = null,
        val genres: List<JsonElement> = emptyList(),
        val chapters: List<ChapterEntry> = emptyList(),
    )

    @Serializable
    private class ChapterText(val contentHtml: String? = null)

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
