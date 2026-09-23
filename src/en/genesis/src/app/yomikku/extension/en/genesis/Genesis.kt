package app.yomikku.extension.en.genesis

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
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Genesis. Ported from LNReader's genesis plugin, updated for the site's current reader: novels and chapter lists
 * come from its JSON api, chapter text from the chapter page, which the site now renders on the server.
 */
class Genesis : HttpSource() {

    override val name = "Genesis"
    override val baseUrl = "https://genesistudio.com"
    override val lang = "en"
    override val supportsLatest = false

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder().add("Referer", "$baseUrl/")

    // Listings: the site has a few dozen novels, all on one list.

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/api/directus/novels?status=published&limit=-1&fields=$LIST_FIELDS", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val novels = json.decodeFromString<List<Novel>>(response.body.string())
        return MangasPage(novels.map { it.toSManga() }, false)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/api/directus/novels?status=published&limit=-1&fields=$LIST_FIELDS#${query.trim()}", headers)

    override fun searchMangaParse(response: Response): MangasPage {
        val query = response.request.url.fragment.orEmpty().normalize()
        val novels = json.decodeFromString<List<Novel>>(response.body.string())
            .filter { it.novel_title.normalize().contains(query) }
        return MangasPage(novels.map { it.toSManga() }, false)
    }

    private fun String.normalize() = lowercase().replace(NON_WORD, "")

    private fun Novel.toSManga() = SManga.create().apply {
        url = "/novels/$abbreviation"
        title = novel_title
        thumbnail_url = coverUrl()
    }

    private fun Novel.coverUrl(): String? {
        val file = coverFile?.filename_disk ?: cover?.let { "$it.png" } ?: return null
        return "$STORAGE/$file"
    }

    // Details

    private fun abbreviationOf(manga: SManga) = manga.url.removePrefix("/novels/").substringBefore('/')

    override fun mangaDetailsRequest(manga: SManga) =
        GET("$baseUrl/api/directus/novels/by-abbreviation/${abbreviationOf(manga)}", headers)

    override fun getMangaUrl(manga: SManga) = baseUrl + manga.url

    override fun mangaDetailsParse(response: Response): SManga {
        val novel = json.decodeFromString<Novel>(response.body.string())
        return novel.toSManga().apply {
            description = listOfNotNull(novel.one_liner, novel.synopsis).filter { it.isNotBlank() }.joinToString("\n\n")
                .ifEmpty { null }
            author = novel.author?.ifBlank { null }
            genre = novel.genres.mapNotNull { it.genres_id?.label }.joinToString().ifEmpty { null }
            status = when (novel.serialization?.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                "dropped", "cancelled" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
        }
    }

    // Chapters

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val novel = client.newCall(mangaDetailsRequest(manga)).awaitSuccess()
            .use { json.decodeFromString<Novel>(it.body.string()) }
        val slug = novel.slug ?: novel.abbreviation
        val chapters = client.newCall(GET("$baseUrl/api/novels-chapter/${novel.id}", headers)).awaitSuccess()
            .use { json.decodeFromString<ChapterList>(it.body.string()).data.chapters }
        return chapters.filter { it.status == null || it.status == "released" }.map { chapter ->
            SChapter.create().apply {
                url = "/novels/$slug/chapter-${number(chapter.chapter_number)}"
                name = (if (chapter.isUnlocked) "" else "🔒 ") + chapter.chapter_title.ifBlank { "Chapter ${number(chapter.chapter_number)}" }
                chapter_number = chapter.chapter_number
                date_upload = chapter.date_published?.let { runCatching { DATE_FORMAT.parse(it.take(19))?.time }.getOrNull() } ?: 0L
            }
        }.sortedByDescending { it.chapter_number }
    }

    private fun number(value: Float) = if (value % 1f == 0f) value.toInt().toString() else value.toString()

    // Text

    override fun chapterTextParse(response: Response): String {
        val content = response.asJsoup().selectFirst(".chapter-content-html")
            ?: throw Exception("This chapter is locked or has no text")
        content.select("script, style").remove()
        return content.html()
    }

    @Serializable
    private class CoverFile(val filename_disk: String? = null)

    @Serializable
    private class GenreLabel(val label: String? = null)

    @Suppress("PropertyName")
    @Serializable
    private class GenreLink(val genres_id: GenreLabel? = null)

    @Suppress("PropertyName")
    @Serializable
    private class Novel(
        val id: String,
        val novel_title: String,
        val abbreviation: String,
        val slug: String? = null,
        val cover: String? = null,
        val coverFile: CoverFile? = null,
        val synopsis: String? = null,
        val one_liner: String? = null,
        val author: String? = null,
        val serialization: String? = null,
        val genres: List<GenreLink> = emptyList(),
    )

    @Suppress("PropertyName")
    @Serializable
    private class Chapter(
        val chapter_number: Float,
        val chapter_title: String = "",
        val status: String? = null,
        val isUnlocked: Boolean = true,
        val date_published: String? = null,
    )

    @Serializable
    private class ChapterData(val chapters: List<Chapter> = emptyList())

    @Serializable
    private class ChapterList(val data: ChapterData)

    companion object {
        private const val STORAGE = "https://api.genesistudio.com/storage/v1/object/public/directus"
        private const val LIST_FIELDS = "%5B%22id%22,%22novel_title%22,%22cover%22,%22abbreviation%22%5D"
        private val NON_WORD = Regex("""[^a-z0-9]""")
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
