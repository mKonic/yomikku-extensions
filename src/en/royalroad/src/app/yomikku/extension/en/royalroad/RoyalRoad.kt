package app.yomikku.extension.en.royalroad

import app.yomikku.lib.lnfilters.LnFilters
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Royal Road. Ported from LNReader's royalroad plugin. */
class RoyalRoad : HttpSource() {

    override val name = "Royal Road"
    override val baseUrl = "https://www.royalroad.com"
    override val lang = "en"
    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }

    override fun getFilterList(): FilterList = LnFilters.fromResource(javaClass, "filters.json")

    // Listings

    private fun searchRequest(page: Int, params: List<Pair<String, String>>): Request {
        val url = "$baseUrl/fictions/search".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
        params.forEach { (key, value) -> url.addQueryParameter(key, value) }
        return GET(url.build(), headers)
    }

    override fun popularMangaRequest(page: Int) = searchRequest(page, listOf("orderBy" to "popularity"))

    override fun popularMangaParse(response: Response) = novelsParse(response)

    override fun latestUpdatesRequest(page: Int) = searchRequest(page, listOf("orderBy" to "last_update"))

    override fun latestUpdatesParse(response: Response) = novelsParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val params = mutableListOf<Pair<String, String>>()
        if (query.isNotBlank()) params += "title" to query.trim()
        params += LnFilters.queryParams(filters)
        // Genres, tags and content warnings all go in the same two lists.
        filters.filterIsInstance<LnFilters.ExcludableGroup>().forEach { group ->
            params += group.included.map { "tagsAdd" to it }
            params += group.excluded.map { "tagsRemove" to it }
        }
        return searchRequest(page, params)
    }

    override fun searchMangaParse(response: Response) = novelsParse(response)

    private fun novelsParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val novels = document.select(".fiction-list-item").mapNotNull { item ->
            val link = item.selectFirst(".fiction-title a[href]") ?: return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                title = link.text().trim()
                thumbnail_url = item.selectFirst("img")?.absUrl("src")
            }
        }
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNext = document.select("ul.pagination a[href]")
            .any { it.absUrl("href").toHttpUrlOrNull()?.queryParameter("page") == "${page + 1}" }
        return MangasPage(novels, hasNext)
    }

    // Details

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h1")?.text()?.trim().orEmpty()
            author = document.selectFirst(".fic-header a[href^='/profile/']")?.text()?.trim()
            thumbnail_url = document.selectFirst("img.thumbnail")?.absUrl("src")
            genre = document.select("span.tags a").joinToString { it.text().trim() }.ifEmpty { null }
            description = document.selectFirst("div.description")?.let { summary ->
                summary.select("p").map { it.wholeText().trim() }.filter { it.isNotEmpty() }
                    .ifEmpty { listOf(summary.text().trim()) }
                    .joinToString("\n\n")
            }
            val labels = document.select(".fiction-info span.label-sm").map { it.text().trim().uppercase() }
            status = when {
                "ONGOING" in labels -> SManga.ONGOING
                "COMPLETED" in labels -> SManga.COMPLETED
                "HIATUS" in labels -> SManga.ON_HIATUS
                "DROPPED" in labels -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
        }
    }

    // Chapters

    override fun chapterListParse(response: Response): List<SChapter> {
        val script = response.body.string()
        val chapters = CHAPTERS.find(script)?.groupValues?.get(1)
            ?.let { json.decodeFromString<List<ChapterEntry>>(it) }
            ?: throw Exception("No chapters found")
        return chapters.map { chapter ->
            SChapter.create().apply {
                url = chapter.url
                name = (if (chapter.isUnlocked == false) "🔒 " else "") + chapter.title
                chapter_number = chapter.order + 1f
                date_upload = runCatching { DATE_FORMAT.parse(chapter.date)?.time }.getOrNull() ?: 0L
            }
        }.reversed()
    }

    // Text

    override fun chapterTextParse(response: Response): String {
        val document = response.asJsoup()
        // Paragraphs of a class the page hides with its own stylesheet say the chapter was stolen from Royal Road.
        val hidden = document.select("style").mapNotNull { HIDDEN_CLASS.find(it.data())?.groupValues?.get(1) }
        val content = document.selectFirst(".chapter-content") ?: throw Exception("No chapter text found")
        hidden.forEach { document.select(".$it").remove() }
        // Author notes come before or after the chapter; keep them where they are, set apart.
        val parts = document.select(".author-note-portlet, .chapter-content").map { part ->
            if (part === content) {
                part.html()
            } else {
                part.select(".portlet-title").remove()
                "<blockquote>${part.selectFirst(".portlet-body")?.html() ?: part.html()}</blockquote>"
            }
        }
        return parts.filter { it.isNotBlank() }.joinToString("<hr>")
    }

    @Serializable
    private class ChapterEntry(
        val title: String,
        val date: String,
        val order: Int,
        val url: String,
        val isUnlocked: Boolean? = null,
    )

    companion object {
        private val CHAPTERS = Regex("""window\.chapters\s*=\s*(\[.*?]);""")
        private val HIDDEN_CLASS = Regex("""\.([\w-]+)\s*\{[^}]*display:\s*none""")
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
