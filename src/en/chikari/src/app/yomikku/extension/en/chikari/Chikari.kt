package app.yomikku.extension.en.chikari

import app.yomikku.lib.lnfilters.LnFilters
import app.yomikku.lib.paced.Paced
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Entities
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Chikari. Ported from LNReader's chikari plugin; everything comes from the site's JSON api. */
class Chikari : HttpSource() {

    override val name = "Chikari"
    override val baseUrl = "https://chikari.moe"
    override val lang = "en"
    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }

    override fun getFilterList(): FilterList = LnFilters.fromResource(javaClass, "filters.json")

    // Listings

    private fun novelsRequest(page: Int, sort: String, build: okhttp3.HttpUrl.Builder.() -> Unit = {}): Request {
        val url = "$baseUrl/api/novels".toHttpUrl().newBuilder()
            .addQueryParameter("sort", sort)
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("offset", ((page - 1) * PAGE_SIZE).toString())
            .apply(build)
            .build()
        return GET(url, headers)
    }

    override fun popularMangaRequest(page: Int) = novelsRequest(page, "popular")

    override fun popularMangaParse(response: Response) = novelsParse(response)

    override fun latestUpdatesRequest(page: Int) = novelsRequest(page, "updated")

    override fun latestUpdatesParse(response: Response) = novelsParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val sort = LnFilters.queryParams(filters).toMap()["sort"] ?: "popular"
        return novelsRequest(page, sort) {
            if (query.isNotBlank()) addQueryParameter("q", query.trim())
            filters.filterIsInstance<LnFilters.ExcludableGroup>().forEach { group ->
                group.included.forEach { addQueryParameter("genre", it) }
                group.excluded.forEach { addQueryParameter("genre_exclude", it) }
            }
        }
    }

    override fun searchMangaParse(response: Response) = novelsParse(response)

    private fun novelsParse(response: Response): MangasPage {
        val list = json.decodeFromString<NovelList>(response.body.string())
        val novels = list.items.map { novel ->
            SManga.create().apply {
                url = "/novels/${novel.slug}"
                title = novel.title
                thumbnail_url = novel.cover_url
            }
        }
        return MangasPage(novels, list.offset + list.items.size < list.total)
    }

    // Details

    private fun slugOf(manga: SManga) = manga.url.substringAfterLast('/')

    override fun mangaDetailsRequest(manga: SManga) = GET("$baseUrl/api/novels/${slugOf(manga)}", headers)

    override fun getMangaUrl(manga: SManga) = baseUrl + manga.url

    override fun mangaDetailsParse(response: Response): SManga {
        val novel = json.decodeFromString<NovelDetails>(response.body.string())
        return SManga.create().apply {
            title = novel.title
            thumbnail_url = novel.cover_url
            author = novel.authors.joinToString { it.name }.ifEmpty { null }
            genre = novel.genres.joinToString { it.name }.ifEmpty { null }
            description = novel.description
            status = when (novel.status) {
                "releasing" -> SManga.ONGOING
                "finished", "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                "cancelled" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
        }
    }

    // Chapters: 500 per call.

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val slug = slugOf(manga)
        val chapters = mutableListOf<ChapterEntry>()
        var offset = 0
        do {
            if (offset > 0) delay(Paced.PAGE_DELAY_MS)
            val url = "$baseUrl/api/novels/$slug/chapters?order=asc&limit=$CHAPTER_PAGE&offset=$offset"
            val list = Paced.fetch(client, GET(url, headers)).use { json.decodeFromString<ChapterList>(it.body.string()) }
            chapters += list.items
            offset += CHAPTER_PAGE
        } while (list.items.size == CHAPTER_PAGE && offset < list.total)
        return chapters.map { chapter ->
            SChapter.create().apply {
                url = "/novels/$slug/chapters/${number(chapter.number)}"
                name = chapter.title ?: "Chapter ${number(chapter.number)}"
                chapter_number = chapter.number
                date_upload = chapter.created_at?.let { runCatching { DATE_FORMAT.parse(it.take(19))?.time }.getOrNull() } ?: 0L
            }
        }.reversed()
    }

    /** "12" for 12.0, "12.5" otherwise, as the api's urls write chapter numbers. */
    private fun number(value: Float) = if (value % 1f == 0f) value.toInt().toString() else value.toString()

    // Text

    override fun chapterTextRequest(chapter: SChapter): Request {
        val (slug, number) = chapter.url.removePrefix("/novels/").split("/chapters/")
        return GET("$baseUrl/api/novels/$slug/chapters/$number/read", headers)
    }

    override fun getChapterUrl(chapter: SChapter) = baseUrl + chapter.url

    override fun chapterTextParse(response: Response): String {
        val chapter = json.decodeFromString<ChapterText>(response.body.string())
        if (chapter.locked) throw Exception(chapter.lock_reason.ifEmpty { "This chapter is locked" })
        return chapter.body.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString("") { "<p>${Entities.escape(it)}</p>" }
    }

    @Suppress("PropertyName")
    @Serializable
    private class NovelEntry(val slug: String, val title: String, val cover_url: String? = null)

    @Serializable
    private class NovelList(val items: List<NovelEntry> = emptyList(), val total: Int = 0, val offset: Int = 0)

    @Serializable
    private class Named(val name: String)

    @Suppress("PropertyName")
    @Serializable
    private class NovelDetails(
        val title: String,
        val cover_url: String? = null,
        val description: String? = null,
        val status: String? = null,
        val genres: List<Named> = emptyList(),
        val authors: List<Named> = emptyList(),
    )

    @Suppress("PropertyName")
    @Serializable
    private class ChapterEntry(val number: Float, val title: String? = null, val created_at: String? = null)

    @Serializable
    private class ChapterList(val items: List<ChapterEntry> = emptyList(), val total: Int = 0)

    @Suppress("PropertyName")
    @Serializable
    private class ChapterText(val body: String = "", val locked: Boolean = false, val lock_reason: String = "")

    companion object {
        private const val PAGE_SIZE = 60
        private const val CHAPTER_PAGE = 500
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
