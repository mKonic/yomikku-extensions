package app.yomikku.multisrc.readnovelfull

import app.yomikku.lib.lnfilters.LnFilters
import app.yomikku.lib.wpcommon.WpCommon.imageUrl
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * The novelfull family of sites (readnovelfull, novelfull, libread, freewebnovel, ...). Ported from LNReader's
 * readnovelfull multisrc plugin.
 *
 * Listings are pages at a path taken from the site's "type" and "genres" filters.
 *
 * @param latestPage path of the latest releases listing.
 * @param searchPage path of the search page.
 * @param chapterList how the site serves the full chapter list; the novel page only shows part of it.
 * @param chapterListing path of the chapter list endpoint, for [ChapterList.ARCHIVE] and [ChapterList.OPTIONS].
 * @param pageAsPath whether listing pages are `<listing>/2` rather than `<listing>?page=2`.
 * @param noPages listings that have a single page.
 * @param searchKey name of the search query parameter.
 * @param postSearch whether the site searches with a form POST, which has no pages.
 */
abstract class ReadNovelFull(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
    private val latestPage: String,
    private val searchPage: String,
    private val chapterList: ChapterList = ChapterList.ARCHIVE,
    private val chapterListing: String = "ajax/chapter-archive",
    private val pageAsPath: Boolean = false,
    private val noPages: List<String> = emptyList(),
    private val searchKey: String = "keyword",
    private val postSearch: Boolean = false,
) : HttpSource() {

    enum class ChapterList {
        /** `<chapterListing>?novelId=<id>` answers with the whole list as links. */
        ARCHIVE,

        /** `<chapterListing>?novelId=<id>` answers with the whole list as a `<select>`. */
        OPTIONS,

        /**
         * A form POST to `<chapterListing>` with the novel's id and slug answers with the whole list as a `<select>`
         * in JSON.
         */
        POST_API,

        /** `<novel>?ajax=chapters&page=<n>` answers with one page of the list as JSON. Slow on long novels. */
        PAGINATED,
    }

    override val supportsLatest = true

    /** The site's filters as LNReader's generator collected them, shipped as a resource of the extension. */
    protected open val filtersResource: String? = null

    override fun getFilterList(): FilterList {
        val resource = filtersResource ?: return FilterList()
        return LnFilters.fromResource(javaClass, resource)
    }

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    private fun url(path: String) = "$baseUrl/${path.trimStart('/')}"

    // Listings

    private fun listingRequest(path: String, page: Int): Request {
        val url = when {
            page == 1 -> url(path)
            pageAsPath -> url("$path/$page")
            else -> url(path).toHttpUrl().newBuilder().addQueryParameter("page", page.toString()).build().toString()
        }
        return GET(url, headers)
    }

    /** The listing [filters] pick: a genre when one is chosen, else the novel type. */
    private fun listingPath(filters: FilterList): String {
        val values = LnFilters.queryParams(filters.ifEmpty { getFilterList() }).toMap()
        return values["genres"]?.takeIf { it.isNotBlank() } ?: values["type"] ?: latestPage
    }

    override fun popularMangaRequest(page: Int) = listingRequest(listingPath(FilterList()), page)

    override fun popularMangaParse(response: Response) = novelsParse(response)

    override fun latestUpdatesRequest(page: Int) = listingRequest(latestPage, page)

    override fun latestUpdatesParse(response: Response) = novelsParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isBlank()) return listingRequest(listingPath(filters), page)
        if (postSearch) {
            return POST(url(searchPage), headers, FormBody.Builder().add(searchKey, query).build())
        }
        val url = url(searchPage).toHttpUrl().newBuilder()
            .addQueryParameter(searchKey, query)
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = novelsParse(response)

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1 && listingPath(FilterList()) in noPages) return MangasPage(emptyList(), false)
        return super.getPopularManga(page)
    }

    protected open fun novelsParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val novels = document.select(".archive h3 a[href], .col-content h3 a[href], .list-novel h3 a[href]")
            .map { link ->
                val row = link.parents().firstOrNull { it.hasClass("row") || it.hasClass("li") } ?: link
                SManga.create().apply {
                    title = link.attr("title").ifBlank { link.text() }.trim()
                    setUrlWithoutDomain(link.absUrl("href"))
                    thumbnail_url = row.selectFirst("img")?.imageUrl()
                }
            }
            .distinctBy { it.url }
        // A form search answers with everything at once; so do the listings in noPages.
        val singlePage = response.request.method == "POST" ||
            response.request.url.encodedPath.trim('/') in noPages
        val next = currentPage(response) + 1
        val hasNext = !singlePage && (
            document.selectFirst(".pagination li.next:not(.disabled) a") != null ||
                document.select(".pages a[href]").any { it.attr("href").trimEnd('/').endsWith("/$next") }
            )
        return MangasPage(novels, hasNext)
    }

    private fun currentPage(response: Response): Int {
        val url = response.request.url
        return url.queryParameter("page")?.toIntOrNull()
            ?: url.pathSegments.lastOrNull()?.toIntOrNull()
            ?: 1
    }

    // Details

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            val cover = document.selectFirst(".books img, .m-imgtxt img, .book img")
            title = document.selectFirst(".books h3.title, .m-desc h1.tit, h1.tit")?.text()?.trim()
                ?: cover?.attr("title")?.trim().orEmpty()
            thumbnail_url = cover?.let {
                it.absUrl("data-cfsrc").ifEmpty { it.imageUrl().orEmpty() }.ifEmpty { null }
            }
            description = document.selectFirst(".desc-text, .m-desc .inner, #novel-summary-inner")
                ?.select("p")?.map { it.text().trim() }?.filter { it.isNotEmpty() }?.joinToString("\n\n")

            // readnovelfull and novelfull: "<h3>Author:</h3> <a>...</a>" rows.
            document.select(".info-meta li, .info > div").forEach { row ->
                val label = row.selectFirst("h3")?.text().orEmpty().lowercase().removeSuffix(":").trim()
                val value = row.select("a").joinToString { it.text().trim() }
                    .ifEmpty { row.ownText().trim() }
                when (label) {
                    "author" -> author = value
                    "genre" -> genre = value
                    "status" -> status = parseStatus(value)
                }
            }
            // libread and freewebnovel: an icon titled "Author" beside its value.
            fun infoOf(title: String) = document.selectFirst(".m-imgtxt span[title=$title] ~ .right")
            infoOf("Author")?.let { author = it.select("a").joinToString { a -> a.text().trim() } }
            infoOf("Genre")?.let { genre = it.select("a").joinToString { a -> a.text().trim() } }
            infoOf("Status")?.let { status = parseStatus(it.text()) }
        }
    }

    private fun parseStatus(text: String) = when (text.trim().lowercase()) {
        "ongoing" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        "hiatus" -> SManga.ON_HIATUS
        "dropped", "cancelled" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    // Chapters

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val novelUrl = url(manga.url)
        val chapters = when (chapterList) {
            ChapterList.PAGINATED -> paginatedChapters(novelUrl)
            ChapterList.POST_API -> {
                val page = client.newCall(GET(novelUrl, headers)).awaitSuccess().asJsoup()
                val id = novelId(page) ?: throw Exception("Could not find the novel's id")
                val body = FormBody.Builder()
                    .add("aid", id)
                    .add("acode", novelUrl.toHttpUrl().pathSegments.last { it.isNotEmpty() }.removeSuffix(".html"))
                    .add("cid", "1")
                    .build()
                val ajaxHeaders = headers.newBuilder()
                    .set("Referer", novelUrl)
                    .add("X-Requested-With", "XMLHttpRequest")
                    .build()
                val json = client.newCall(POST(url(chapterListing), ajaxHeaders, body)).awaitSuccess()
                    .use { Json.parseToJsonElement(it.body.string()).jsonObject }
                json["error"]?.jsonPrimitive?.content?.let { throw Exception(it) }
                Jsoup.parse(json["html"]?.jsonPrimitive?.content.orEmpty(), baseUrl)
                    .select("option[value]").map { chapter(it.absUrl("value"), it.text()) }
            }
            ChapterList.ARCHIVE, ChapterList.OPTIONS -> {
                val page = client.newCall(GET(novelUrl, headers)).awaitSuccess().asJsoup()
                val id = novelId(page) ?: throw Exception("Could not find the novel's id")
                val listUrl = url(chapterListing).toHttpUrl().newBuilder().addQueryParameter("novelId", id).build()
                val html = client.newCall(GET(listUrl, headers)).awaitSuccess().body.string()
                val list = Jsoup.parse(html, baseUrl)
                if (chapterList == ChapterList.OPTIONS) {
                    list.select("option[value]").map { chapter(it.absUrl("value"), it.text()) }
                } else {
                    list.select("a[href]").map { chapter(it.absUrl("href"), it.attr("title").ifBlank { it.text() }) }
                }
            }
        }
        // The sites list oldest first; the app wants newest first.
        return chapters.distinctBy { it.url }.reversed()
    }

    private fun novelId(page: Document): String? =
        page.selectFirst("#rating[data-novel-id]")?.attr("data-novel-id")
            ?: page.selectFirst("[data-novel-id]")?.attr("data-novel-id")
            ?: page.selectFirst("a.set-case[data-articleid]")?.attr("data-articleid")
            ?: page.selectFirst("#indexListPage[data-article-id]")?.attr("data-article-id")

    private suspend fun paginatedChapters(novelUrl: String): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        var page = 1
        var totalPages = 1
        do {
            val url = novelUrl.toHttpUrl().newBuilder()
                .addQueryParameter("ajax", "chapters")
                .addQueryParameter("page", page.toString())
                .addQueryParameter("pageSize", PAGE_SIZE.toString())
                .build()
            if (page > 1) delay(PAGE_DELAY_MS)
            val json = fetchPaced(GET(url, headers)).use { Json.parseToJsonElement(it.body.string()) }
            val html = json.jsonObject["html"]?.jsonPrimitive?.content.orEmpty()
            totalPages = json.jsonObject["totalPage"]?.jsonPrimitive?.int ?: totalPages
            Jsoup.parse(html, baseUrl).select("a[href]").mapTo(chapters) {
                chapter(it.absUrl("href"), it.attr("title").ifBlank { it.text() })
            }
            page++
        } while (page <= totalPages)
        return chapters
    }

    /**
     * The sites answer 429 to a burst of chapter-list pages. Waits as long as the site asks (Retry-After), or a
     * little longer each time, before trying again.
     */
    private suspend fun fetchPaced(request: Request): Response {
        repeat(MAX_ATTEMPTS) { attempt ->
            val response = client.newCall(request).await()
            if (response.code != 429) {
                if (!response.isSuccessful) {
                    response.close()
                    throw HttpException(response.code)
                }
                return response
            }
            val wait = response.header("Retry-After")?.toLongOrNull()?.times(1000) ?: (3000L * (attempt + 1))
            response.close()
            delay(wait)
        }
        throw HttpException(429)
    }

    private fun chapter(href: String, title: String) = SChapter.create().apply {
        setUrlWithoutDomain(href)
        name = title.trim()
    }

    // Text

    override fun chapterTextParse(response: Response): String {
        val document = response.asJsoup()
        val content = document.selectFirst("#chr-content, #chapter-content, .txt")
            ?: throw Exception("No chapter text found")
        content.select(JUNK).remove()
        cleanChapterText(content, response.request.url.toString())
        content.select("img").forEach { img -> img.imageUrl()?.let { img.attr("src", it) } }
        return content.html()
    }

    /** Site-specific cleanup of a chapter's content, after the theme's own. [url] is the chapter page's. */
    protected open fun cleanChapterText(content: Element, url: String) = Unit

    companion object {
        private const val PAGE_SIZE = 40
        private const val PAGE_DELAY_MS = 150L
        private const val MAX_ATTEMPTS = 4

        /** Ads, scripts, unlock prompts and the reader widgets the sites put inside the chapter. */
        private const val JUNK = "script, style, ins, noscript, iframe, sub, .reader-ad-skip, div[class*=ads], " +
            "div[id*=ads], .unlock-buttons, [class*=app-promo], .fwn-slot-host, .chapter-extra-actions"
    }
}
