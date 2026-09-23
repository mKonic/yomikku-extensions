package app.yomikku.extension.en.wntl

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Response
import org.jsoup.nodes.Entities
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Web Novel Translation (wntl.net). Ported from LNReader's wntl plugin. The api returns the whole catalogue at once;
 * chapters are Markdown files.
 */
class Wntl : HttpSource() {

    override val name = "Web Novel Translation"
    override val baseUrl = "https://wntl.net"
    override val lang = "en"
    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun catalogue(): List<Novel> =
        client.newCall(GET("$baseUrl/api/novels", headers)).awaitSuccess()
            .use { json.decodeFromString<NovelList>(it.body.string()).novels }

    private fun toManga(novel: Novel) = SManga.create().apply {
        url = "/novel/${novel.id}"
        title = novel.title
        thumbnail_url = novel.cover?.let { if (it.startsWith("http")) it else baseUrl + "/" + it.trimStart('/') }
        author = novel.author?.replace(' ', ' ')?.trim()
        description = novel.description
        genre = novel.genre.joinToString().ifEmpty { null }
        val state = novel.status.joinToString(" ").lowercase()
        status = when {
            "ongoing" in state -> SManga.ONGOING
            "complete" in state -> SManga.COMPLETED
            "hiatus" in state -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        initialized = true
    }

    // Listings

    override suspend fun getPopularManga(page: Int) =
        MangasPage(catalogue().sortedByDescending { it.chapterCount }.map(::toManga), false)

    override suspend fun getLatestUpdates(page: Int) =
        MangasPage(catalogue().sortedByDescending { it.latestChapter?.publishedAt.orEmpty() }.map(::toManga), false)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val term = query.trim()
        val matches = catalogue().filter { novel ->
            novel.title.contains(term, ignoreCase = true) || novel.alternateTitles.any { it.contains(term, ignoreCase = true) }
        }
        return MangasPage(matches.map(::toManga), false)
    }

    // Details

    private fun idOf(manga: SManga) = manga.url.substringAfterLast('/')

    override suspend fun getMangaDetails(manga: SManga): SManga =
        catalogue().firstOrNull { it.id == idOf(manga) }?.let(::toManga) ?: throw Exception("Novel not found")

    // Chapters

    override fun chapterListRequest(manga: SManga) = GET("$baseUrl/api/chapters/${idOf(manga)}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val id = response.request.url.pathSegments.last()
        return json.decodeFromString<ChapterList>(response.body.string()).chapters
            .filter { it.status == "published" }
            .map { chapter ->
                SChapter.create().apply {
                    url = "/novel/$id/${chapter.file}"
                    name = chapter.title
                    chapter_number = chapter.number
                    date_upload = runCatching { DATE_FORMAT.parse(chapter.publishedAt.take(19))?.time }.getOrNull() ?: 0L
                }
            }.reversed()
    }

    // Text

    override fun chapterTextRequest(chapter: SChapter) =
        GET("$baseUrl/api/chapter-content/" + chapter.url.removePrefix("/novel/"), headers)

    override fun getChapterUrl(chapter: SChapter) = baseUrl + chapter.url.substringBeforeLast('/')

    override fun chapterTextParse(response: Response): String = markdownToHtml(response.body.string())

    /** The subset of Markdown the chapters use: headings, rules, bold and italics. */
    private fun markdownToHtml(markdown: String) = markdown.lines().map { it.trim() }.filter { it.isNotEmpty() }
        .joinToString("\n") { line ->
            val heading = HEADING.matchEntire(line)
            when {
                heading != null -> {
                    val level = heading.groupValues[1].length
                    "<h$level>${inline(heading.groupValues[2])}</h$level>"
                }
                RULE.matches(line) -> "<hr>"
                else -> "<p>${inline(line)}</p>"
            }
        }

    private fun inline(text: String) = Entities.escape(text)
        .replace(BOLD, "<b>$1</b>")
        .replace(ITALIC, "<i>$1</i>")

    @Serializable
    private class ChapterRef(val publishedAt: String? = null)

    @Serializable
    private class Novel(
        val id: String,
        val title: String,
        val author: String? = null,
        val cover: String? = null,
        val description: String? = null,
        val genre: List<String> = emptyList(),
        val status: List<String> = emptyList(),
        @kotlinx.serialization.SerialName("alternate-title") val alternateTitles: List<String> = emptyList(),
        val chapterCount: Int = 0,
        val latestChapter: ChapterRef? = null,
    )

    @Serializable
    private class NovelList(val novels: List<Novel> = emptyList())

    @Serializable
    private class Chapter(
        val number: Float,
        val title: String,
        val publishedAt: String = "",
        val file: String,
        val status: String = "",
    )

    @Serializable
    private class ChapterList(val chapters: List<Chapter> = emptyList())

    companion object {
        private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
        private val RULE = Regex("""^(-{3,}|\*{3,}|_{3,})$""")
        private val BOLD = Regex("""\*\*(.+?)\*\*""")
        private val ITALIC = Regex("""\*([^*]+)\*""")
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
