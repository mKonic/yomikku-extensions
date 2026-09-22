package app.yomikku.multisrc.novelfire

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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Novel Fire and its sister sites. Ported from LNReader's novelfire multisrc plugin.
 */
abstract class NovelFire(
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

    private fun searchAdvRequest(page: Int, filters: FilterList, sort: String?): Request {
        val url = "$baseUrl/search-adv".toHttpUrl().newBuilder()
        val values = LnFilters.queryParams(filters.ifEmpty { getFilterList() })
        values.filterNot { (key, _) -> key == "sort" && sort != null }.forEach { (key, value) ->
            url.addQueryParameter(PARAMS[key] ?: key, value)
        }
        if (sort != null) url.addQueryParameter("sort", sort)
        url.addQueryParameter("page", page.toString())
        return GET(url.build(), headers)
    }

    override fun popularMangaRequest(page: Int) = searchAdvRequest(page, FilterList(), "rank-top")

    override fun popularMangaParse(response: Response) = novelsParse(response.asJsoup(), ".novel-item")

    override fun latestUpdatesRequest(page: Int) = searchAdvRequest(page, FilterList(), "date")

    override fun latestUpdatesParse(response: Response) = novelsParse(response.asJsoup(), ".novel-item")

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isBlank()) return searchAdvRequest(page, filters, null)
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", query)
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val selector = if (response.request.url.encodedPath == "/search") ".novel-list.chapters .novel-item" else ".novel-item"
        return novelsParse(response.asJsoup(), selector)
    }

    private fun novelsParse(document: Document, selector: String): MangasPage {
        document.checkCloudflare()
        val novels = document.select(selector).mapNotNull { item ->
            val link = item.selectFirst("> a[href]") ?: item.selectFirst("h4 a[href]") ?: return@mapNotNull null
            SManga.create().apply {
                title = link.attr("title").ifBlank { item.selectFirst("h4")?.text().orEmpty() }.trim()
                setUrlWithoutDomain(link.absUrl("href"))
                thumbnail_url = item.selectFirst(".novel-cover > img")
                    ?.let { it.absUrl("data-src").ifEmpty { it.absUrl("src") } }
            }
        }.distinctBy { it.url }
        val hasNext = document.selectFirst(".pagination a[rel=next], .pagination li:last-child:not(.disabled) a") != null
        return MangasPage(novels, hasNext)
    }

    private fun Document.checkCloudflare() {
        if ("Cloudflare" in title()) throw Exception("Cloudflare is blocking requests, please open in WebView")
    }

    // Details

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup().also { it.checkCloudflare() }
        return SManga.create().apply {
            val cover = document.selectFirst(".cover > img")
            title = document.selectFirst(".novel-title")?.text()?.trim()?.ifEmpty { null }
                ?: cover?.attr("alt").orEmpty()
            thumbnail_url = cover?.let { it.absUrl("data-src").ifEmpty { it.absUrl("src") } }
            genre = document.select(".categories .property-item").joinToString { it.text().trim() }
            author = document.selectFirst(".author .property-item > span")?.text()?.trim()
            description = document.selectFirst(".summary .content")?.let { summary ->
                summary.select(".expand").remove()
                summary.select("p").map { it.text().trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
                    .ifEmpty { summary.text().trim() }
            }
            val state = (
                document.selectFirst(".header-stats .ongoing")?.text()
                    ?: document.selectFirst(".header-stats .completed")?.text()
                ).orEmpty().trim().lowercase()
            status = when (state) {
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
        val page = client.newCall(GET(baseUrl + manga.url, headers)).awaitSuccess().asJsoup()
        page.checkCloudflare()
        val postId = page.selectFirst("#novel-report")?.attr("report-post_id")?.ifBlank { null }
            ?: throw Exception("Could not find the novel's id")
        // The table behind the chapters page hands out every chapter at once when asked for length -1.
        val listUrl = "$baseUrl/ajax/listChapterDataAjax".toHttpUrl().newBuilder().apply {
            TABLE_PARAMS.forEach { (key, value) -> addQueryParameter(key, value) }
            addQueryParameter("start", "0")
            addQueryParameter("length", "-1")
            addQueryParameter("post_id", postId)
            addQueryParameter("only_bookmark", "false")
        }.build()
        val ajaxHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl${manga.url}/chapters")
            .add("X-Requested-With", "XMLHttpRequest")
            .build()
        val body = client.newCall(GET(listUrl, ajaxHeaders)).awaitSuccess().body.string()
        if ("You are being rate limited" in body) throw Exception("Novel Fire is rate limiting requests, try again later")
        val data = Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray.orEmpty()
        return data.mapNotNull { element ->
            val row = element.jsonObject
            val number = row["n_sort"]?.jsonPrimitive?.content?.toFloatOrNull() ?: return@mapNotNull null
            val title = (row["title"] ?: row["slug"])?.jsonPrimitive?.content.orEmpty()
            SChapter.create().apply {
                url = "${manga.url}/chapter-${number.toString().removeSuffix(".0")}"
                name = Jsoup.parse(title).text().replace(ZERO_WIDTH, "").trim()
                chapter_number = number
            }
        }.sortedByDescending { it.chapter_number }
    }

    override fun chapterListParse(response: Response) = throw UnsupportedOperationException()

    // Text

    override fun chapterTextParse(response: Response): String {
        val document = response.asJsoup().also { it.checkCloudflare() }
        val content = document.selectFirst("#content") ?: throw Exception("Chapter text not found, try again")
        content.select("script, style, ins, noscript, iframe").remove()
        // The site slips made-up elements (<nf...>) carrying watermark text into chapters.
        content.getAllElements().filter { it.normalName().startsWith("nf") }.forEach { it.remove() }
        return content.html().replace("&nbsp;", " ")
    }

    companion object {
        /** Filter keys as LNReader names them, and the search-adv parameter each one sets. */
        private val PARAMS = mapOf(
            "language" to "country_id[]",
            "genre_operator" to "ctgcon",
            "genres" to "categories[]",
            "chapters" to "totalchapter",
            "rating_operator" to "ratcon",
            "tags" to "tags[]",
            "tags_excluded" to "tags_excluded[]",
        )

        private val TABLE_PARAMS = listOf(
            "draw" to "1",
            "columns[0][data]" to "n_sort",
            "columns[0][name]" to "cmm_posts_detail.n_sort",
            "columns[0][searchable]" to "true",
            "columns[0][orderable]" to "true",
            "columns[0][search][value]" to "",
            "columns[0][search][regex]" to "false",
            "columns[1][data]" to "bookmark_created_at",
            "columns[1][name]" to "bookmark_chapters.created_at",
            "columns[1][searchable]" to "false",
            "columns[1][orderable]" to "true",
            "columns[1][search][value]" to "",
            "columns[1][search][regex]" to "false",
            "order[0][column]" to "0",
            "order[0][dir]" to "asc",
            "order[0][name]" to "cmm_posts_detail.n_sort",
            "search[value]" to "",
            "search[regex]" to "false",
        )

        private val ZERO_WIDTH = Regex("[\\u200B-\\u200D\\uFEFF]")
    }
}
