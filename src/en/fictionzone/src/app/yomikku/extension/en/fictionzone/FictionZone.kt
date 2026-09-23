package app.yomikku.extension.en.fictionzone

import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Entities
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Fiction Zone. Ported from LNReader's fictionzone plugin. Everything goes through the proxy the site's own pages call,
 * which forwards a path to its platform api.
 */
class FictionZone : HttpSource() {

    override val name = "Fiction Zone"
    override val baseUrl = "https://fictionzone.net"
    override val lang = "en"
    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }

    private fun api(path: String): Request {
        val body = buildJsonObject {
            put("path", path)
            putJsonArray("headers") { addJsonArray { add("content-type"); add("application/json") } }
            put("method", "GET")
        }
        return POST("$baseUrl/api/__api_party/fictionzone", headers, body.toString().toRequestBody(JSON))
    }

    private inline fun <reified T> Response.data(): T =
        json.decodeFromString<Envelope<T>>(body.string()).data ?: throw Exception("Fiction Zone returned nothing")

    // Listings

    private fun browse(page: Int, sortBy: String, query: String? = null): Request {
        // Only the path and query are sent; the proxy adds the api's host.
        val path = "$baseUrl/platform/browse".toHttpUrl().newBuilder().apply {
            if (query != null) addQueryParameter("search", query).addQueryParameter("search_in_synopsis", "true")
            addQueryParameter("page", page.toString())
            addQueryParameter("page_size", "20")
            addQueryParameter("sort_by", sortBy)
            addQueryParameter("sort_order", "desc")
        }.build()
        return api(path.encodedPath + "?" + path.encodedQuery)
    }

    override fun popularMangaRequest(page: Int) = browse(page, "bookmark_count")

    override fun popularMangaParse(response: Response) = novelsParse(response)

    override fun latestUpdatesRequest(page: Int) = browse(page, "created_at")

    override fun latestUpdatesParse(response: Response) = novelsParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) =
        browse(page, "bookmark_count", query.trim().ifEmpty { null })

    override fun searchMangaParse(response: Response) = novelsParse(response)

    private fun novelsParse(response: Response): MangasPage {
        val data = response.data<NovelList>()
        val novels = data.novels.map { novel ->
            SManga.create().apply {
                url = "/novel/${novel.slug}"
                title = novel.title
                thumbnail_url = cover(novel.image)
            }
        }
        return MangasPage(novels, data.pagination?.has_next == true)
    }

    /** The api gives each cover as its url in base64, which the site's image proxy takes as is. */
    private fun cover(image: String?) = image?.let { "https://cdn.fictionzone.net/insecure/rs:fill:330:500/$it.webp" }

    // Details

    private fun slugOf(url: String) = url.removePrefix("/novel/").substringBefore('/')

    private suspend fun details(manga: SManga): NovelDetails =
        client.newCall(api("/platform/novel-details?slug=${slugOf(manga.url)}")).awaitSuccess().data()

    override fun getMangaUrl(manga: SManga) = baseUrl + manga.url

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val novel = details(manga)
        return SManga.create().apply {
            title = novel.title
            thumbnail_url = cover(novel.image)
            author = novel.contributors.filter { it.role == "author" }.joinToString { it.display_name }.ifEmpty { null }
            description = novel.synopsis?.trim()
            genre = (novel.genres + novel.tags).joinToString { it.name }.ifEmpty { null }
            status = when (novel.status.primitive()) {
                "1" -> SManga.ONGOING
                "2" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            initialized = true
        }
    }

    // Chapters: the whole list comes in one call.

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val novelId = details(manga).id.primitive()
        val request = api("/platform/chapter-lists?novel_id=$novelId")
        val chapters = client.newCall(request).awaitSuccess().data<ChapterList>().chapters
        return chapters.map { chapter ->
            SChapter.create().apply {
                // The novel's id rides along for the content call.
                url = "${manga.url}/${chapter.chapter_id.primitive()}?novel_id=$novelId"
                val locked = chapter.status != null && chapter.status != "unlocked"
                name = (if (locked) "🔒 " else "") + chapter.title
                chapter_number = chapter.chapter_number ?: -1f
                date_upload = chapter.published_date?.let { runCatching { DATE_FORMAT.parse(it)?.time }.getOrNull() } ?: 0L
            }
        }.reversed()
    }

    // Text

    /** Chapter pages turn curl away, so WebView opens the novel. */
    override fun getChapterUrl(chapter: SChapter) = baseUrl + chapter.url.substringBeforeLast('/')

    override suspend fun getChapterText(chapter: SChapter): String {
        val url = (baseUrl + chapter.url).toHttpUrl()
        val chapterId = url.pathSegments.last()
        val novelId = url.queryParameter("novel_id")
        val request = api("/platform/chapter-content?novel_id=$novelId&chapter_id=$chapterId")
        val content = client.newCall(request).awaitSuccess().data<ChapterContent>().content
            ?: throw Exception("This chapter is locked")
        return content.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString("") { "<p>${Entities.escape(it)}</p>" }
    }

    private fun JsonElement.primitive() = (this as? JsonPrimitive)?.content.orEmpty()

    @Suppress("PropertyName")
    @Serializable
    private class Envelope<T>(val data: T? = null)

    @Suppress("PropertyName")
    @Serializable
    private class Pagination(val has_next: Boolean = false)

    @Serializable
    private class NovelList(val novels: List<NovelEntry> = emptyList(), val pagination: Pagination? = null)

    @Serializable
    private class NovelEntry(val title: String, val slug: String, val image: String? = null)

    @Serializable
    private class Named(val name: String)

    @Suppress("PropertyName")
    @Serializable
    private class Contributor(val role: String? = null, val display_name: String = "")

    @Serializable
    private class NovelDetails(
        val id: JsonElement,
        val title: String,
        val image: String? = null,
        val synopsis: String? = null,
        val status: JsonElement = JsonPrimitive(""),
        val genres: List<Named> = emptyList(),
        val tags: List<Named> = emptyList(),
        val contributors: List<Contributor> = emptyList(),
    )

    @Suppress("PropertyName")
    @Serializable
    private class ChapterEntry(
        val chapter_id: JsonElement,
        val title: String,
        val chapter_number: Float? = null,
        val published_date: String? = null,
        val status: String? = null,
    )

    @Serializable
    private class ChapterList(val chapters: List<ChapterEntry> = emptyList())

    @Serializable
    private class ChapterContent(val content: String? = null)

    companion object {
        private val JSON = "application/json".toMediaType()
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
