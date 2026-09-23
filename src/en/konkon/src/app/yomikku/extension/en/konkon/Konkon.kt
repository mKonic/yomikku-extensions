package app.yomikku.extension.en.konkon

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone

/** Konkon. Ported from LNReader's konkon plugin; everything comes from the site's JSON api. */
class Konkon : HttpSource() {

    override val name = "Konkon"
    override val baseUrl = "https://konkon.ink"
    override val lang = "en"
    override val supportsLatest = true

    private val api = "https://api-k.konkon.ink"

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Accept", "application/json")
        .add("Referer", "$baseUrl/")

    // Listings: trending takes only a count, so each page asks for everything up to its end and keeps its part.

    override fun popularMangaRequest(page: Int) = GET("$api/api/public/novels_trending?limit=${page * PAGE_SIZE}", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val novels = json.decodeFromString<Listing>(response.body.string()).data
        val page = response.request.url.queryParameter("limit")!!.toInt() / PAGE_SIZE
        val part = novels.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE)
        return MangasPage(part.map { it.toSManga() }, novels.size == page * PAGE_SIZE)
    }

    override fun latestUpdatesRequest(page: Int) =
        GET("$api/api/public/latest-updates?per_page=$PAGE_SIZE&page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val novels = json.decodeFromString<Listing>(response.body.string()).data
        return MangasPage(novels.map { it.toSManga() }, novels.size == PAGE_SIZE)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$api/api/public/search".toHttpUrl().newBuilder().addQueryParameter("q", query.trim()).build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val novels = json.decodeFromString<SearchResult>(response.body.string()).results
        return MangasPage(novels.map { it.toSManga() }, false)
    }

    private fun Novel.toSManga() = SManga.create().apply {
        url = "/read/$slug"
        title = this@toSManga.title
        thumbnail_url = cover(featured_image_thumb_medium_key, featured_image_key, featured_image_thumb_small_key, featured_image)
    }

    /** The api serves images by their storage key, base64-encoded into the path; the first key given is used. */
    private fun cover(vararg keys: String?): String? = keys.firstNotNullOfOrNull { it?.ifBlank { null } }
        ?.let { "$api/api/media/k/" + Base64.getEncoder().encodeToString(it.toByteArray(Charsets.ISO_8859_1)) }

    // Details and chapters come from the same call, a hundred chapters a page.

    private fun slugOf(manga: SManga) = manga.url.removePrefix("/read/").substringBefore('?')

    private fun novelRequest(slug: String, page: Int) = GET("$api/api/public/novels/$slug?page=$page&per_page=100", headers)

    override fun mangaDetailsRequest(manga: SManga) = novelRequest(slugOf(manga), 1)

    override fun getMangaUrl(manga: SManga) = baseUrl + manga.url

    override fun mangaDetailsParse(response: Response): SManga {
        val novel = json.decodeFromString<Details>(response.body.string()).data
        return SManga.create().apply {
            url = "/read/${novel.slug}"
            title = novel.title
            thumbnail_url = with(novel) {
                cover(featured_image_thumb_medium_key, featured_image_key, featured_image_thumb_small_key, featured_image)
            }
            author = novel.author_name
            genre = (novel.genres + novel.tags).map { it.name }.filter { it.isNotBlank() }.distinct()
                .joinToString().ifEmpty { null }
            description = novel.description?.let { html ->
                val body = Jsoup.parseBodyFragment(html).body()
                val paragraphs = body.select("p").map { it.text().trim() }.filter { it.isNotEmpty() }
                (if (paragraphs.isNotEmpty()) paragraphs.joinToString("\n\n") else body.text()).trim().ifEmpty { null }
            }
            status = when (novel.novel_status?.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "completed", "complete" -> SManga.COMPLETED
                "cancelled", "canceled" -> SManga.CANCELLED
                "hiatus", "on hiatus" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val slug = slugOf(manga)
        val pages = mutableListOf<NovelData>()
        var page = 1
        do {
            if (page > 1) delay(PAGE_DELAY_MS)
            pages += client.newCall(novelRequest(slug, page)).awaitSuccess()
                .use { json.decodeFromString<Details>(it.body.string()).data }
            val last = pages.first().chapters_pagination?.last_page ?: 1
            page++
        } while (page <= last)

        val chapters = pages.flatMap { it.volumes }
            .sortedBy { it.order ?: 0 }
            .flatMap { volume -> volume.chapters.sortedBy { it.sort_order ?: 0 } }
            .filter { it.status == "published" }
        return chapters.mapIndexed { index, chapter ->
            val locked = chapter.is_locked && !chapter.user_has_access
            SChapter.create().apply {
                url = "/read/chapter/${chapter.id}/${chapter.slug}"
                name = (if (locked) "🔒 " else "") + chapter.title
                chapter_number = index + 1f
                date_upload = (chapter.scheduled_for ?: chapter.created_at)
                    ?.let { runCatching { DATE_FORMAT.parse(it.take(19))?.time }.getOrNull() } ?: 0L
            }
        }.reversed()
    }

    // Text

    override fun chapterTextRequest(chapter: SChapter): Request {
        val id = CHAPTER_ID.find(chapter.url)?.groupValues?.get(1) ?: throw Exception("Invalid chapter path")
        return GET("$api/api/public/chapters/$id", headers)
    }

    override fun getChapterUrl(chapter: SChapter) = baseUrl + chapter.url

    override fun chapterTextParse(response: Response): String {
        val chapter = json.decodeFromString<ChapterText>(response.body.string()).data
        if ((chapter.locked || chapter.is_locked) && !chapter.user_has_access) throw Exception("This chapter is locked")
        val content = chapter.content ?: throw Exception("Konkon returned no chapter content")
        val body = Jsoup.parseBodyFragment(content).body()
        body.select("script, style").remove()
        // Colours set for the site's own theme would clash with the reader's.
        body.select("[style]").forEach { element ->
            val style = element.attr("style").split(';').map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("color", ignoreCase = true) }
                .joinToString(";")
            if (style.isEmpty()) element.removeAttr("style") else element.attr("style", style)
        }
        return body.html()
    }

    @Suppress("PropertyName")
    @Serializable
    private class Novel(
        val title: String = "Untitled",
        val slug: String,
        val featured_image: String? = null,
        val featured_image_key: String? = null,
        val featured_image_thumb_small_key: String? = null,
        val featured_image_thumb_medium_key: String? = null,
    )

    @Serializable
    private class Listing(val data: List<Novel> = emptyList())

    @Serializable
    private class SearchResult(val results: List<Novel> = emptyList())

    @Serializable
    private class Named(val name: String = "")

    @Suppress("PropertyName")
    @Serializable
    private class Pagination(val last_page: Int? = null)

    @Suppress("PropertyName")
    @Serializable
    private class Chapter(
        val id: Long,
        val title: String,
        val slug: String,
        val status: String = "",
        val sort_order: Int? = null,
        val is_locked: Boolean = false,
        val user_has_access: Boolean = false,
        val scheduled_for: String? = null,
        val created_at: String? = null,
    )

    @Serializable
    private class Volume(val order: Int? = null, val chapters: List<Chapter> = emptyList())

    @Suppress("PropertyName")
    @Serializable
    private class NovelData(
        val title: String = "Untitled",
        val slug: String,
        val featured_image: String? = null,
        val featured_image_key: String? = null,
        val featured_image_thumb_small_key: String? = null,
        val featured_image_thumb_medium_key: String? = null,
        val author_name: String? = null,
        val description: String? = null,
        val novel_status: String? = null,
        val genres: List<Named> = emptyList(),
        val tags: List<Named> = emptyList(),
        val volumes: List<Volume> = emptyList(),
        val chapters_pagination: Pagination? = null,
    )

    @Serializable
    private class Details(val data: NovelData)

    @Suppress("PropertyName")
    @Serializable
    private class ChapterData(
        val content: String? = null,
        val locked: Boolean = false,
        val is_locked: Boolean = false,
        val user_has_access: Boolean = false,
    )

    @Serializable
    private class ChapterText(val data: ChapterData)

    companion object {
        private const val PAGE_SIZE = 20
        private const val PAGE_DELAY_MS = 500L
        private val CHAPTER_ID = Regex("""/read/chapter/(\d+)""")
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
