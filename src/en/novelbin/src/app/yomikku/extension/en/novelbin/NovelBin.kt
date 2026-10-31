package app.yomikku.extension.en.novelbin

import app.yomikku.lib.lnfilters.LnFilters
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response

/**
 * Novel Bin. Ported from LNReader's novelbin plugin. The site is a single-page app reading JSON files off a CDN that
 * answers only requests carrying the site as Origin; the site's own /api/cdn/ route that LNReader uses now returns
 * the app's page instead.
 */
class NovelBin : HttpSource() {

    override val name = "Novel Bin"
    override val baseUrl = "https://novelbin.xyz"
    override val lang = "en"
    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    override fun getFilterList(): FilterList = LnFilters.fromResource(javaClass, "filters.json")

    private fun cdn(file: String) = GET("$CDN/$file", headers)

    // Listings

    // The popular list is one unpaged list.
    override fun popularMangaRequest(page: Int) = cdn("lists/web_popular.json")

    override fun popularMangaParse(response: Response) =
        MangasPage(json.decodeFromString<List<ListNovel>>(response.body.string()).map { it.toSManga() }, false)

    override fun latestUpdatesRequest(page: Int) = cdn("lists/web_latest_$page.json")

    override fun latestUpdatesParse(response: Response) = listPageParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val values = LnFilters.queryParams(filters).toMap()
        val genre = values["genre"].orEmpty()
        val status = values["status"].orEmpty()
        return when {
            genre.isNotEmpty() -> cdn("lists/web_genre_${genre}_$page.json")
            status.isNotEmpty() -> cdn("lists/web_status_${status}_$page.json")
            else -> latestUpdatesRequest(page)
        }
    }

    override fun searchMangaParse(response: Response) = listPageParse(response)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return super.getSearchManga(page, query, filters)
        // The site searches one ~7.5 MB index on the client; it is loaded once per process.
        val term = query.trim().lowercase()
        val matches = searchIndex().filter {
            it.title.lowercase().contains(term) || it.alternative_titles.orEmpty().lowercase().contains(term)
        }
        val from = (page - 1) * SEARCH_PAGE_SIZE
        return MangasPage(
            matches.drop(from).take(SEARCH_PAGE_SIZE).map { it.toSManga() },
            matches.size > from + SEARCH_PAGE_SIZE,
        )
    }

    private val searchLock = Mutex()
    private var searchEntries: List<ListNovel>? = null

    private suspend fun searchIndex(): List<ListNovel> = searchLock.withLock {
        searchEntries ?: client.newCall(cdn("lists/web_search.json")).awaitSuccess()
            .use { json.decodeFromString<List<ListNovel>>(it.body.string()) }
            .also { searchEntries = it }
    }

    private fun listPageParse(response: Response): MangasPage {
        val page = json.decodeFromString<ListPage>(response.body.string())
        return MangasPage(page.data.map { it.toSManga() }, page.has_more)
    }

    private fun ListNovel.toSManga() = SManga.create().apply {
        url = "/novel/$slug"
        title = this@toSManga.title
        thumbnail_url = cover
    }

    // Details

    private fun slugOf(url: String) = url.trimStart('/').split('/')[1]

    override fun mangaDetailsRequest(manga: SManga) = cdn("novel/${slugOf(manga.url)}.json")

    override fun getMangaUrl(manga: SManga) = baseUrl + manga.url

    override fun mangaDetailsParse(response: Response): SManga {
        val novel = json.decodeFromString<NovelDetail>(response.body.string())
        return SManga.create().apply {
            url = "/novel/${novel.slug}"
            title = novel.title
            thumbnail_url = novel.cover
            author = novel.author?.ifBlank { null }
            description = novel.description?.ifBlank { null }
            genre = novel.genres.joinToString().ifEmpty { null }
            status = when (novel.status) {
                "Ongoing" -> SManga.ONGOING
                "Completed" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    // Chapters

    private suspend fun novelId(slug: String): Long =
        client.newCall(cdn("novel/$slug.json")).awaitSuccess().use { json.decodeFromString<NovelDetail>(it.body.string()).id }

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val slug = slugOf(manga.url)
        val id = novelId(slug)
        val chapters = client.newCall(cdn("chapters/$id.json")).awaitSuccess()
            .use { json.decodeFromString<List<ChapterEntry>>(it.body.string()) }
        return chapters.sortedByDescending { it.index }.map { chapter ->
            SChapter.create().apply {
                // The novel id rides along so reading a chapter needs no lookup.
                url = "/read/$slug/chapter-${chapter.index + 1}#$id"
                name = chapter.title
                chapter_number = (chapter.index + 1).toFloat()
            }
        }
    }

    override fun chapterListParse(response: Response) = throw UnsupportedOperationException()

    // Text

    override fun getChapterUrl(chapter: SChapter) = baseUrl + chapter.url.substringBefore('#')

    override suspend fun getChapterText(chapter: SChapter): String {
        val path = chapter.url.substringBefore('#')
        val id = chapter.url.substringAfter('#', "").toLongOrNull() ?: novelId(slugOf(path))
        val index = path.substringAfterLast("chapter-").toInt() - 1
        val content = client.newCall(cdn("read/$id/$index.json")).awaitSuccess()
            .use { json.decodeFromString<ChapterContent>(it.body.string()).content }
        return content?.ifBlank { null } ?: throw Exception("Chapter not found, it may have been removed from this source")
    }

    override fun chapterTextParse(response: Response) = throw UnsupportedOperationException()

    @Serializable
    private class ListNovel(
        val title: String,
        val slug: String,
        val cover: String? = null,
        val alternative_titles: String? = null,
    )

    @Serializable
    private class ListPage(val has_more: Boolean = false, val data: List<ListNovel> = emptyList())

    @Serializable
    private class NovelDetail(
        val id: Long,
        val title: String,
        val slug: String,
        val author: String? = null,
        val description: String? = null,
        val cover: String? = null,
        val genres: List<String> = emptyList(),
        val status: String? = null,
    )

    @Serializable
    private class ChapterEntry(val title: String, val index: Int)

    @Serializable
    private class ChapterContent(val content: String? = null)

    companion object {
        private const val CDN = "https://boxnovela.b-cdn.net"
        private const val SEARCH_PAGE_SIZE = 50
    }
}
