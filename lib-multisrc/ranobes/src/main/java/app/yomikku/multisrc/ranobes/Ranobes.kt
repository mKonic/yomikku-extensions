package app.yomikku.multisrc.ranobes

import app.yomikku.lib.paced.Paced
import app.yomikku.lib.wpcommon.WpCommon.checkBlocked
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The Ranobes sites (ranobes.top in English, ranobes.com in Russian), built on DataLife Engine. Ported from LNReader's
 * ranobes multisrc plugin.
 *
 * @param path the section novels live under ("novels", "ranobe").
 * @param postSearch whether the site searches with DLE's form POST rather than `/search/<query>/`.
 */
abstract class Ranobes(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
    private val path: String,
    private val postSearch: Boolean = false,
) : HttpSource() {

    override val supportsLatest = false

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    // Listings

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/$path/page/$page/", headers)

    override fun popularMangaParse(response: Response) = novelsParse(response)

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (!postSearch) return GET("$baseUrl/search/$query/page/$page/", headers)
        val body = FormBody.Builder()
            .add("do", "search")
            .add("subaction", "search")
            .add("search_start", page.toString())
            .add("story", query)
            .build()
        return POST("$baseUrl/index.php?do=search", headers, body)
    }

    override fun searchMangaParse(response: Response) = novelsParse(response)

    private fun novelsParse(response: Response): MangasPage {
        val document = response.asJsoup().checkBlocked(response, baseUrl)
        val novels = document.select(".short-cont").mapNotNull { card ->
            val link = card.selectFirst("h2.title a[href]") ?: return@mapNotNull null
            SManga.create().apply {
                title = link.text().trim()
                setUrlWithoutDomain(link.absUrl("href"))
                thumbnail_url = card.selectFirst("figure")?.attr("style")
                    ?.let { BACKGROUND_URL.find(it)?.groupValues?.get(1) }
            }
        }
        val hasNext = document.selectFirst(".pages a[href], .navigation a[href]:contains(»)") != null && novels.isNotEmpty()
        return MangasPage(novels, hasNext)
    }

    // Details

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup().checkBlocked(response, baseUrl)
        return SManga.create().apply {
            val poster = document.selectFirst(".poster img")
            title = poster?.attr("alt")?.trim().orEmpty()
            thumbnail_url = poster?.absUrl("src")
            author = document.select("[itemprop=creator]").joinToString { it.text().trim() }.ifEmpty { null }
            genre = document.select("#mc-fs-genre a").joinToString { it.text().trim() }
            description = document.selectFirst("[itemprop=description], .moreless.cont-text")?.let { summary ->
                summary.select("br").append("\\n")
                summary.text().replace("\\n", "\n").lines().joinToString("\n") { it.trim() }.trim()
            }
            val state = document.select("li[title]")
                .firstOrNull { it.attr("title").contains("Original status") || it.attr("title").contains("Статус оригинала") }
                ?.selectFirst("a")?.text()?.trim()
            status = when (state) {
                "Ongoing", "В процессе" -> SManga.ONGOING
                null -> SManga.UNKNOWN
                else -> SManga.COMPLETED
            }
        }
    }

    // Chapters

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        // The table of contents pages through all chapters, 25 at a time, at /chapters/<novel id>/.
        val listUrl = NOVEL_ID.find(manga.url.substringAfterLast('/'))?.let { "$baseUrl/chapters/${it.groupValues[1]}/" }
            ?: client.newCall(GET(baseUrl + manga.url, headers)).awaitSuccess().asJsoup()
                .select("a[href]").map { it.absUrl("href") }.firstOrNull { CHAPTER_LIST.matches(it) }
            ?: throw Exception("No chapter list found")
        val chapters = mutableListOf<SChapter>()
        var page = 1
        var pages = 1
        do {
            // The site shows a captcha to anyone who pages through quickly.
            if (page > 1) delay(PAGE_DELAY_MS)
            val url = if (page == 1) listUrl else "${listUrl}page/$page/"
            val response = Paced.fetch(client, GET(url, headers))
            val document = response.use { it.asJsoup().checkBlocked(it, baseUrl) }
            pages = maxOf(pages, pageCount(document))
            chapters += chaptersOf(document)
            page++
        } while (page <= pages)
        return chapters.distinctBy { it.url }
    }

    override fun chapterListParse(response: Response) = throw UnsupportedOperationException()

    private fun pageCount(document: Document): Int {
        data(document)?.get("pages_count")?.jsonPrimitive?.int?.let { return it }
        return document.select(".pages a[href]").mapNotNull { PAGE_NUMBER.find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 1
    }

    private fun chaptersOf(document: Document): List<SChapter> {
        val json = data(document)?.get("chapters")?.jsonArray
        if (json != null) {
            return json.map { element ->
                val chapter = element.jsonObject
                SChapter.create().apply {
                    setUrlWithoutDomain(chapter["link"]!!.jsonPrimitive.content)
                    name = chapter["title"]?.jsonPrimitive?.content.orEmpty().trim()
                    date_upload = chapter["date"]?.jsonPrimitive?.content?.let { date ->
                        runCatching { DATE_FORMAT.parse(date)?.time }.getOrNull()
                    } ?: 0L
                }
            }
        }
        return document.select(".cat_block.cat_line a[href][title]").map { link ->
            SChapter.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                name = link.attr("title").trim()
            }
        }
    }

    private fun data(document: Document) = document.select("script").firstNotNullOfOrNull { script ->
        script.data().substringAfter("window.__DATA__ =", "").trim().removeSuffix(";").ifEmpty { null }
    }?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }

    // Text

    override fun chapterTextParse(response: Response): String {
        val document = response.asJsoup().checkBlocked(response, baseUrl)
        val content = document.selectFirst("div.text#arrticle") ?: throw Exception("No chapter text found")
        content.select("script, style, ins, noscript, iframe, .free-support, .ads-desktop, .ads-mobile").remove()
        return content.html()
    }

    companion object {
        private const val PAGE_DELAY_MS = 5000L
        private val NOVEL_ID = Regex("""^(\d+)-""")
        private val BACKGROUND_URL = Regex("""url\(['"]?(.*?)['"]?\)""")
        private val CHAPTER_LIST = Regex("""https?://[^/]+/chapters/[^/]+/""")
        private val PAGE_NUMBER = Regex("""/page/(\d+)/""")
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }
}
