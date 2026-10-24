package app.yomikku.extension.en.pawread

import app.yomikku.lib.lnfilters.LnFilters
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * PawRead, on its mobile site. Ported from LNReader's pawread plugin. Listings are path-built (`/list/<filters>/<sort>/`)
 * and a novel's page holds its whole chapter list.
 */
class PawRead : HttpSource() {

    override val name = "PawRead"
    override val baseUrl = "https://m.pawread.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun getFilterList(): FilterList = LnFilters.fromResource(javaClass, "filters.json")

    // Listings

    private fun listRequest(page: Int, path: String = "", sort: String) =
        GET("$baseUrl/list/${if (path.isEmpty()) "" else "$path/"}$sort/?page=$page", headers)

    override fun popularMangaRequest(page: Int) = listRequest(page, sort = "click")

    override fun popularMangaParse(response: Response) = listParse(response)

    override fun latestUpdatesRequest(page: Int) = listRequest(page, sort = "update")

    override fun latestUpdatesParse(response: Response) = listParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            val url = "$baseUrl/search/".toHttpUrl().newBuilder().addQueryParameter("keywords", query.trim()).build()
            return GET(url, headers)
        }
        val params = LnFilters.queryParams(filters).toMap()
        val path = listOfNotNull(params["genre"], params["status"], params["lang"]).joinToString("-")
        // The site's own descending order is the default; the switch prefixes the sort with "-" like its links do.
        val sort = (if (params["order"] == "true") "-" else "") + (params["sort"] ?: "click")
        return listRequest(page, path, sort)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        if (response.request.url.encodedPath.startsWith("/list/")) return listParse(response)
        // Search answers every page with the same results, so it is one page.
        val novels = response.asJsoup().select(".itemBox").mapNotNull { item ->
            val link = item.selectFirst("a.title[href]") ?: return@mapNotNull null
            SManga.create().apply {
                url = link.attr("href")
                title = link.text().trim()
                thumbnail_url = item.selectFirst("img")?.absUrl("src")
            }
        }
        return MangasPage(novels, false)
    }

    private fun listParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val novels = document.select("li.list-comic").mapNotNull { item ->
            val link = item.selectFirst("a.txtA[href]") ?: return@mapNotNull null
            SManga.create().apply {
                url = link.attr("href")
                title = link.text().trim()
                thumbnail_url = item.selectFirst("img")?.absUrl("src")
            }
        }
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        return MangasPage(novels, document.selectFirst("a[data-page=$page]") != null)
    }

    // Details

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val cover = document.selectFirst("#Cover img")
        // Status, author and views, in that order.
        val lines = document.select("p.txtItme").map { it.text().trim() }
        return SManga.create().apply {
            title = cover?.attr("title")?.trim().orEmpty()
            thumbnail_url = cover?.absUrl("src")
            author = lines.getOrNull(1)?.ifEmpty { null }
            genre = document.select("a.btn-default").joinToString { it.text().trim() }
            description = document.selectFirst("#full-des")?.wholeText()?.trim()
            status = when (lines.getOrNull(0)?.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }

    // Chapters

    override fun chapterListParse(response: Response): List<SChapter> {
        val rows = response.asJsoup().select("div.item-box[onclick]")
        return rows.mapIndexedNotNull { index, row ->
            val path = CHAPTER_PATH.find(row.attr("onclick"))?.groupValues?.get(1) ?: return@mapIndexedNotNull null
            val title = row.selectFirst("div > span")?.text()?.trim().orEmpty()
            // Early-access chapters carry a GOLD badge and only show a teaser.
            val early = row.selectFirst(".ea-badge") != null
            SChapter.create().apply {
                url = path
                name = if (early) "🔒 $title" else title
                chapter_number = (index + 1).toFloat()
                date_upload = row.children().lastOrNull { it.tagName() == "span" }?.text()?.trim()
                    ?.let { runCatching { DATE_FORMAT.parse(it)?.time }.getOrNull() }
                    ?.takeIf { it > 0 } ?: 0L
            }
        }.reversed()
    }

    // Text

    override fun chapterTextParse(response: Response): String {
        val content = response.asJsoup().selectFirst("div.main div.content") ?: throw Exception("No chapter text found")
        content.select("script, style").remove()
        // "Read the most updated version ... at pawread dot com" lines, and link-shortener spam.
        content.select("p").filter { p -> WATERMARKS.any { it in p.text().lowercase() } }.forEach { it.remove() }
        return content.html()
    }

    companion object {
        private val CHAPTER_PATH = Regex("""episode_click\('([^']+)'""")
        private val DATE_FORMAT = SimpleDateFormat("yyyy.MM.dd", Locale.ENGLISH)
        private val WATERMARKS = listOf("pawread", "tinyurl", "bit.ly")
    }
}
