package app.yomikku.multisrc.readwn

import app.yomikku.lib.lnfilters.LnFilters
import app.yomikku.lib.wpcommon.WpCommon
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
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document

/**
 * The readwn family of sites (wuxiabox, fanmtl, wuxiaspot, ...), all run on the same Empire CMS setup. Ported from
 * LNReader's readwn multisrc plugin.
 */
abstract class Readwn(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
) : HttpSource() {

    override val supportsLatest = true

    /** The site's filters as LNReader's generator collected them, shipped as a resource of the extension. */
    protected open val filtersResource: String? = null

    override fun getFilterList(): FilterList {
        val resource = filtersResource ?: return FilterList()
        return LnFilters.fromResource(javaClass, resource)
    }

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    // Listings

    /** `/list/<genre>/<status>-<sort>-<page from 0>.html`, or a tag's single page. */
    private fun listRequest(page: Int, filters: FilterList, sort: String?): Request {
        val values = LnFilters.queryParams(filters.ifEmpty { getFilterList() }).toMap()
        values["tags"]?.takeIf { it.isNotBlank() }?.let { return GET("$baseUrl/tags/$it-0.html", headers) }
        val genre = values["genres"]?.ifBlank { null } ?: "all"
        val status = values["status"]?.ifBlank { null } ?: "all"
        val order = sort ?: values["sort"]?.ifBlank { null } ?: "newstime"
        return GET("$baseUrl/list/$genre/$status-$order-${page - 1}.html", headers)
    }

    override fun popularMangaRequest(page: Int) = listRequest(page, FilterList(), null)

    override fun popularMangaParse(response: Response) = novelsParse(response.asJsoup(), paged = true)

    override fun latestUpdatesRequest(page: Int) = listRequest(page, FilterList(), "lastdotime")

    override fun latestUpdatesParse(response: Response) = novelsParse(response.asJsoup(), paged = true)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isBlank()) return listRequest(page, filters, null)
        val body = FormBody.Builder()
            .add("show", "title")
            .add("tempid", "1")
            .add("tbname", "news")
            .add("keyboard", query)
            .build()
        val searchHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/search.html")
            .add("Origin", baseUrl)
            .build()
        return POST("$baseUrl/e/search/index.php", searchHeaders, body)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        // A search answers with every match at once; a tag listing has a single page too.
        val paged = response.request.method == "GET" && "/tags/" !in response.request.url.encodedPath
        return novelsParse(response.asJsoup(), paged)
    }

    private fun novelsParse(document: Document, paged: Boolean): MangasPage {
        val novels = document.select("li.novel-item").mapNotNull { item ->
            val link = item.selectFirst("a[href]") ?: return@mapNotNull null
            val title = item.selectFirst("h4")?.text()?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            SManga.create().apply {
                this.title = title
                setUrlWithoutDomain(link.absUrl("href"))
                thumbnail_url = item.selectFirst("img")?.let { it.absUrl("data-src").ifEmpty { it.absUrl("src") } }
            }
        }
        return MangasPage(novels, paged && novels.isNotEmpty())
    }

    // Details

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h1.novel-title")?.text()?.trim().orEmpty()
            author = document.selectFirst("span[itemprop=author]")?.text()?.trim()
            thumbnail_url = document.selectFirst("figure.cover > img")
                ?.let { it.absUrl("data-src").ifEmpty { it.absUrl("src") } }
            description = document.selectFirst(".summary")?.text()?.removePrefix("Summary")?.trim()
            genre = document.select("div.categories > ul > li").joinToString { it.text().trim() }
            status = when (stat(document, "Status")) {
                "Ongoing" -> SManga.ONGOING
                "Completed" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    private fun stat(document: Document, label: String): String? =
        document.select("div.header-stats > span")
            .firstOrNull { it.selectFirst("small")?.text()?.trim() == label }
            ?.selectFirst("strong")?.text()?.trim()

    // Chapters

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val page = client.newCall(GET(baseUrl + manga.url, headers)).awaitSuccess().asJsoup()
        // The novel page lists the first chapters only; /e/extend/fy.php serves the whole list 100 at a time.
        val id = manga.url.substringAfterLast('/').substringBefore(".html")
        val total = stat(page, "Chapters")?.filter { it.isDigit() }?.toIntOrNull() ?: 0
        val pages = maxOf(1, (total + LIST_PAGE_SIZE - 1) / LIST_PAGE_SIZE)
        val chapters = mutableListOf<SChapter>()
        for (index in 0 until pages) {
            if (index > 0) delay(PAGE_DELAY_MS)
            val list = client.newCall(GET("$baseUrl/e/extend/fy.php?page=$index&wjm=$id", headers))
                .awaitSuccess().asJsoup()
            list.select(".chapter-list li").mapNotNullTo(chapters) { item ->
                val link = item.selectFirst("a[href]") ?: return@mapNotNullTo null
                val href = link.absUrl("href")
                val number = CHAPTER_NUMBER.find(href)?.groupValues?.get(1)
                val title = item.selectFirst(".chapter-title")?.text()?.trim().orEmpty()
                SChapter.create().apply {
                    setUrlWithoutDomain(href)
                    // Some sites label chapters "Page N"; the URL says it's chapter N.
                    name = if (number != null && (title.isEmpty() || PAGE_TITLE.matches(title))) {
                        "Chapter $number"
                    } else {
                        title
                    }
                    chapter_number = number?.toFloatOrNull() ?: -1f
                    date_upload = item.selectFirst(".chapter-update")?.text()?.let { WpCommon.parseDate(it) } ?: 0L
                }
            }
        }
        return chapters.distinctBy { it.url }.reversed()
    }

    override fun chapterListParse(response: Response) = throw UnsupportedOperationException()

    // Text

    override fun chapterTextParse(response: Response): String {
        val content = response.asJsoup().selectFirst(".chapter-content") ?: throw Exception("No chapter text found")
        content.select("script, style, ins, noscript, iframe").remove()
        return content.html()
    }

    companion object {
        private const val LIST_PAGE_SIZE = 100
        private const val PAGE_DELAY_MS = 150L
        private val CHAPTER_NUMBER = Regex("_(\\d+)\\.html")
        private val PAGE_TITLE = Regex("Page \\d+")
    }
}
