package app.yomikku.extension.en.kdtnovels

import app.yomikku.lib.lnfilters.LnFilters
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * KDT Novels. Ported from LNReader's kdtnovels plugin: the site left its LightNovelWP theme for its own Next.js pages.
 * Listings are `/search/` cards; a series page only renders the open volume's chapters, so the whole list is read
 * from the React Server Components payload the page streams in `self.__next_f.push` calls.
 */
class KdtNovels : HttpSource() {

    override val name = "KDT Novels"
    override val baseUrl = "https://kdtnovels.net"
    override val lang = "en"
    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }

    override fun getFilterList(): FilterList = LnFilters.fromResource(javaClass, "filters.json")

    // Listings

    private fun searchRequest(page: Int, build: okhttp3.HttpUrl.Builder.() -> Unit = {}): Request {
        val url = "$baseUrl/search/".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .apply(build)
            .build()
        return GET(url, headers)
    }

    // The site sorts by last update only, so popular and latest are the same list.
    override fun popularMangaRequest(page: Int) = searchRequest(page)

    override fun popularMangaParse(response: Response) = cardsParse(response)

    override fun latestUpdatesRequest(page: Int) = searchRequest(page)

    override fun latestUpdatesParse(response: Response) = cardsParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val genre = LnFilters.queryParams(filters).toMap()["genre"]
        return searchRequest(page) {
            if (query.isNotBlank()) addQueryParameter("q", query.trim())
            if (!genre.isNullOrEmpty()) addQueryParameter("genre", genre)
        }
    }

    override fun searchMangaParse(response: Response) = cardsParse(response)

    private fun cardsParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val novels = document.select("a.group[href^=/series/]").map { card ->
            val heading = card.selectFirst("h3")
            SManga.create().apply {
                url = card.attr("href")
                title = heading?.attr("title")?.ifBlank { null } ?: heading?.text().orEmpty()
                thumbnail_url = card.selectFirst("img")?.absUrl("src")
            }
        }
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNext = document.selectFirst("a[href*=page=${page + 1}]") != null
        return MangasPage(novels, hasNext)
    }

    // Details

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val heading = document.selectFirst("main h1")
        return SManga.create().apply {
            title = heading?.text()?.trim().orEmpty()
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.absUrl("content")
            genre = document.select("main a[href^=/search/?genre=]").joinToString { it.text().trim() }
            val summary = document.selectFirst("main .reader-content")
            description = summary?.select("p")?.map { it.text().trim() }?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
                ?: summary?.text()?.trim()
            // "<author> · Art: <artist>" right below the title, when the site credits anyone; the view counter after
            // it is a .flex paragraph.
            val credits = heading?.parent()?.nextElementSiblings()?.firstOrNull { it.tagName() == "p" && !it.hasClass("flex") }
                ?.text()?.split("· Art:")
            author = credits?.getOrNull(0)?.trim()?.ifEmpty { null }
            artist = credits?.getOrNull(1)?.trim()?.ifEmpty { null }
            // Only finished or paused series carry a badge beside the title.
            status = when (heading?.siblingElements()?.firstOrNull { it.tagName() == "span" }?.text()?.trim()?.lowercase()) {
                null, "" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }

    // Chapters

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val html = client.newCall(GET(baseUrl + manga.url, headers)).awaitSuccess().body.string()
        val groups = chapterGroups(html) ?: throw Exception("Could not find the chapter list")
        val chapters = mutableListOf<SChapter>()
        for (group in groups) {
            val label = (group as? JsonObject)?.get("label")?.jsonPrimitive?.contentOrNull.orEmpty()
            val entries = (group as? JsonObject)?.get("chapters") as? JsonArray ?: continue
            for (entry in entries) {
                val fields = entry as? JsonObject ?: continue
                val slug = fields["slug"]?.jsonPrimitive?.contentOrNull ?: continue
                val number = fields["chapter_no"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val subtitle = fields["subtitle"]?.jsonPrimitive?.contentOrNull
                val locked = fields["locked"]?.jsonPrimitive?.booleanOrNull == true
                chapters += SChapter.create().apply {
                    url = "/$slug/"
                    val title = "$label · Ch. $number" + if (subtitle.isNullOrBlank()) "" else ": $subtitle"
                    name = if (locked) "🔒 $title" else title
                    // Volumes restart their numbering, so the list position is the only order that holds.
                    chapter_number = (chapters.size + 1).toFloat()
                }
            }
        }
        return chapters.reversed()
    }

    /** The volume groups in the page's flight payload: the first `groups` array whose entries all hold `chapters`. */
    private fun chapterGroups(html: String): JsonArray? {
        val flight = FLIGHT_PUSH.findAll(html).joinToString("") { json.decodeFromString<String>(it.groupValues[1]) }
        for (line in flight.lineSequence()) {
            if ("\"groups\":[" !in line) continue
            val row = runCatching { json.parseToJsonElement(line.substringAfter(':')) }.getOrNull() ?: continue
            findGroups(row)?.let { return it }
        }
        return null
    }

    private fun findGroups(node: JsonElement): JsonArray? = when (node) {
        is JsonObject -> (node["groups"] as? JsonArray)
            ?.takeIf { groups -> groups.all { (it as? JsonObject)?.get("chapters") is JsonArray } }
            ?: node.values.firstNotNullOfOrNull(::findGroups)
        is JsonArray -> node.firstNotNullOfOrNull(::findGroups)
        else -> null
    }

    // Text

    override fun chapterTextParse(response: Response): String {
        val document = response.asJsoup()
        val content = document.selectFirst("article.reader-content") ?: throw Exception(lockedOrMissing(document))
        content.select("script, style, noscript").remove()
        // Illustrations are placeholders the site's script swaps for images.
        content.select(".kdt-ilus[data-ilus]").forEach { placeholder ->
            val src = placeholder.absUrl("data-ilus")
            if (src.isEmpty()) placeholder.remove() else placeholder.replaceWith(Element("img").attr("src", src))
        }
        return content.html()
    }

    private fun lockedOrMissing(document: Document) =
        if (document.select("main h2").text().contains("This chapter is locked")) {
            "This chapter is locked. Unlock it on KDT Novels to read it."
        } else {
            "No chapter text found"
        }

    companion object {
        private val FLIGHT_PUSH = Regex("""self\.__next_f\.push\(\[1,("(?:[^"\\]|\\.)*")]\)""")
    }
}
