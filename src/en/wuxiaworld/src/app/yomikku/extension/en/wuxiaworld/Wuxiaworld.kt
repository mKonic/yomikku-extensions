package app.yomikku.extension.en.wuxiaworld

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup

/**
 * Wuxiaworld. Ported from LNReader's wuxiaworld plugin. The catalogue comes from the site's JSON api; novels, chapter
 * lists and chapters from the gRPC-web api its pages use.
 */
class Wuxiaworld : HttpSource() {

    override val name = "Wuxiaworld"
    override val baseUrl = "https://www.wuxiaworld.com"
    override val lang = "en"
    override val supportsLatest = false

    private val json = Json { ignoreUnknownKeys = true }

    // Listings: the api returns the whole catalogue at once.

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/api/novels", headers)

    override fun popularMangaParse(response: Response) = novelsParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isBlank()) return popularMangaRequest(page)
        val url = "$baseUrl/api/novels/search".toHttpUrl().newBuilder().addQueryParameter("query", query.trim()).build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = novelsParse(response)

    private fun novelsParse(response: Response): MangasPage {
        val novels = json.decodeFromString<NovelList>(response.body.string()).items.map { novel ->
            SManga.create().apply {
                url = "/novel/${novel.slug}/"
                title = novel.name
                thumbnail_url = novel.coverUrl
            }
        }
        return MangasPage(novels, false)
    }

    // Details

    private fun slugOf(manga: SManga) = manga.url.trim('/').substringAfter("novel/").substringBefore('/')

    private fun grpc(method: String, body: ProtoWriter): Request {
        val headers = headersBuilder().set("Content-Type", GRPC_WEB).build()
        return POST("$API.$method", headers, body.toGrpcWeb().toRequestBody(GRPC_WEB.toMediaType()))
    }

    private suspend fun getNovel(manga: SManga): ProtoMessage {
        val request = grpc("Novels/GetNovel", ProtoWriter().string(2, slugOf(manga)))
        val body = client.newCall(request).awaitSuccess().use { it.body.bytes() }
        return ProtoMessage.fromGrpcWeb(body).message(1) ?: throw Exception("Novel not found")
    }

    override fun getMangaUrl(manga: SManga) = baseUrl + manga.url

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val novel = getNovel(manga)
        return SManga.create().apply {
            title = novel.string(2).orEmpty()
            thumbnail_url = novel.wrappedString(10)
            author = novel.wrappedString(13)
            genre = novel.strings(16).joinToString().ifEmpty { null }
            description = listOfNotNull(novel.wrappedString(8), novel.wrappedString(9))
                .map { Jsoup.parse(it).text().trim() }
                .filter { it.isNotEmpty() && it != ".." }
                .joinToString("\n\n")
            // proto3 leaves out a field at its default, and the default status (0) is Finished.
            status = when (novel.long(4) ?: 0L) {
                0L -> SManga.COMPLETED
                1L -> SManga.ONGOING
                2L -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
            initialized = true
        }
    }

    // Chapters

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val novel = getNovel(manga)
        val novelId = novel.long(1) ?: throw Exception("Novel not found")
        // Chapters past this number need karma or a subscription unless the novel is free.
        val maxFree = novel.message(14)?.decimal(3) ?: DEFAULT_FREE_CHAPTERS
        val request = grpc("Chapters/GetChapterList", ProtoWriter().int(1, novelId))
        val body = client.newCall(request).awaitSuccess().use { it.body.bytes() }
        val groups = ProtoMessage.fromGrpcWeb(body).messages(1)
        return groups.flatMap { group -> group.messages(6) }.map { item ->
            val number = item.decimal(4) ?: 0.0
            val unlocked = item.message(16)?.message(1)?.bool(1)
            val locked = unlocked == false || (unlocked == null && number > maxFree)
            SChapter.create().apply {
                url = "${manga.url}${item.string(3)}"
                name = (if (locked) "🔒 " else "") + item.string(2).orEmpty()
                // The site's own number reads book 1 chapter 1 as 1.001; the offset just counts up.
                chapter_number = item.int(17)?.toFloat() ?: number.toFloat()
                date_upload = (item.message(18)?.long(1) ?: 0L) * 1000
            }
        }.reversed()
    }

    // Text

    override fun getChapterUrl(chapter: SChapter) = baseUrl + chapter.url

    override suspend fun getChapterText(chapter: SChapter): String {
        val (novelSlug, chapterSlug) = chapter.url.trim('/').substringAfter("novel/").split('/')
        val request = grpc(
            "Chapters/GetChapter",
            ProtoWriter().message(1) {
                message(2) {
                    string(1, novelSlug)
                    string(2, chapterSlug)
                }
            },
        )
        val body = client.newCall(request).awaitSuccess().use { it.body.bytes() }
        val content = ProtoMessage.fromGrpcWeb(body).message(1)?.wrappedString(5)
            ?: throw Exception("This chapter is locked. Unlock it on Wuxiaworld first.")
        // Older chapters still carry the links of the site's old chapter navigation.
        val document = Jsoup.parseBodyFragment(content)
        document.select("p, div").filter { NAVIGATION.matches(it.text().trim()) }.forEach { it.remove() }
        return document.body().html()
    }

    @Serializable
    private class NovelList(val items: List<NovelEntry> = emptyList())

    @Serializable
    private class NovelEntry(val name: String, val slug: String, val coverUrl: String? = null)

    companion object {
        private const val API = "https://api2.wuxiaworld.com/wuxiaworld.api.v2"
        private const val GRPC_WEB = "application/grpc-web+proto"
        private const val DEFAULT_FREE_CHAPTERS = 50.0
        private val NAVIGATION = Regex("""(?i)(previous|next) chapter(\s*\|?\s*(previous|next) chapter)?""")
    }
}
