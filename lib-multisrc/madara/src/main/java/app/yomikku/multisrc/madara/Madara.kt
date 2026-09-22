package app.yomikku.multisrc.madara

import app.yomikku.lib.lnfilters.LnFilters
import app.yomikku.lib.wpcommon.WpCommon
import app.yomikku.lib.wpcommon.WpCommon.checkBlocked
import app.yomikku.lib.wpcommon.WpCommon.imageUrl
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.Locale

/**
 * Sites built on the Madara WordPress theme with the WP Manga plugin, used for novels. Ported from LNReader's madara
 * multisrc plugin.
 *
 * @param useNewChapterEndpoint whether the chapter list is served from `<novel>/ajax/chapters/` rather than
 * `wp-admin/admin-ajax.php`, which newer versions of the plugin do.
 */
abstract class Madara(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
    private val useNewChapterEndpoint: Boolean = false,
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

    private fun listingRequest(page: Int, query: String, filters: FilterList, orderBy: String?): Request {
        val url = "$baseUrl/page/$page/".toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .addQueryParameter("post_type", "wp-manga")
        if (orderBy != null) url.addQueryParameter("m_orderby", orderBy)
        LnFilters.queryParams(filters)
            .filterNot { (key, _) -> key == "m_orderby" && orderBy != null }
            .forEach { (key, value) -> url.addQueryParameter(key, value) }
        return GET(url.build(), headers)
    }

    override fun popularMangaRequest(page: Int) = listingRequest(page, "", FilterList(), "views")

    override fun popularMangaParse(response: Response) = novelsParse(response)

    override fun latestUpdatesRequest(page: Int) = listingRequest(page, "", FilterList(), "latest")

    override fun latestUpdatesParse(response: Response) = novelsParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) =
        listingRequest(page, query, filters, orderBy = null)

    override fun searchMangaParse(response: Response) = novelsParse(response)

    protected open fun novelsParse(response: Response): MangasPage {
        val document = response.asJsoup().checkBlocked(response, baseUrl)
        document.select(".manga-title-badges").remove()
        val novels = document.select(".page-item-detail, .c-tabs-item__content").mapNotNull { element ->
            val link = element.selectFirst(".post-title a") ?: return@mapNotNull null
            val title = element.selectFirst(".post-title")?.text()?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            SManga.create().apply {
                this.title = title
                setUrlWithoutDomain(link.absUrl("href"))
                thumbnail_url = element.selectFirst("img")?.imageUrl()
            }
        }
        val hasNext = document.selectFirst(".nav-previous a, .wp-pagenavi .nextpostslink, a.next") != null ||
            novels.size >= PAGE_SIZE_HINT
        return MangasPage(novels, hasNext)
    }

    // Details

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup().checkBlocked(response, baseUrl)
        document.select(".manga-title-badges, #manga-title span").remove()
        return SManga.create().apply {
            title = document.selectFirst(".post-title h1, #manga-title h1, .manga-title")?.text()?.trim().orEmpty()
            thumbnail_url = document.selectFirst(".summary_image > a > img")?.imageUrl()

            val genres = mutableListOf<String>()
            document.select(".post-content_item, .post-content").forEach { item ->
                val label = item.selectFirst("h5")?.text()?.trim().orEmpty()
                val detail = item.selectFirst(".summary-content, .summary_content") ?: return@forEach
                when (label) {
                    in GENRE_LABELS -> genres += detail.select("a").map { it.text() }
                    in AUTHOR_LABELS -> author = detail.text().trim()
                    in STATUS_LABELS -> status = parseStatus(detail.text())
                    "Artist(s)" -> artist = detail.text().trim()
                }
            }
            if (genres.isEmpty()) genres += document.select(".genres-content a").map { it.text() }
            genre = genres.distinct().joinToString()
            if (status == SManga.UNKNOWN) {
                document.selectFirst(".manga-status")?.let { status = parseStatus(it.text()) }
            }
            if (author.isNullOrBlank()) {
                author = document.selectFirst(".manga-author a, .manga-authors")?.text()?.trim()
            }

            document.select("div.summary__content .code-block, div.summary__content script, noscript").remove()
            description = document.selectFirst("div.summary__content")?.wholeTextParagraphs()
                ?: document.selectFirst("#tab-manga-about")?.text()?.trim()
                ?: document.select(".manga-summary p, .manga-excerpt p").joinToString("\n\n") { it.text() }
                    .ifBlank { null }
        }
    }

    // Chapters

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val novelUrl = baseUrl + manga.url.let { if (it.endsWith("/")) it else "$it/" }
        val html = if (useNewChapterEndpoint) {
            newEndpointChapters(novelUrl)
        } else {
            val page = client.newCall(GET(novelUrl, headers)).awaitSuccess().asJsoup()
            val novelId = page.selectFirst(".rating-post-id")?.attr("value")
                ?: page.selectFirst("#manga-chapters-holder")?.attr("data-id")
                ?: ""
            val body = FormBody.Builder()
                .add("action", "manga_get_chapters")
                .add("manga", novelId)
                .build()
            val response = client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", headers, body)).awaitSuccess()
            response.body.string().takeUnless { it == "0" } ?: page.outerHtml()
        }
        val document = Jsoup.parse(html, baseUrl)
        return document.select(".wp-manga-chapter").mapNotNull { element ->
            val link = element.selectFirst("a") ?: return@mapNotNull null
            val href = link.absUrl("href").takeUnless { it.isBlank() || it.endsWith("#") } ?: return@mapNotNull null
            val locked = element.hasClass("premium-block")
            SChapter.create().apply {
                setUrlWithoutDomain(href)
                name = (if (locked) "🔒 " else "") + link.text().trim()
                date_upload = element.selectFirst("span.chapter-release-date")?.text()?.let(::parseDate) ?: 0L
            }
        }
    }

    private suspend fun newEndpointChapters(novelUrl: String): String {
        val ajax = "${novelUrl}ajax/chapters/"
        val ajaxHeaders = headers.newBuilder().set("Referer", novelUrl).build()
        val first = client.newCall(POST(ajax, ajaxHeaders)).awaitSuccess().body.string()
        val pages = Jsoup.parse(first).select(".pagination a[data-page]")
        if (pages.isEmpty()) return first
        val maxPage = pages.maxOf { it.attr("data-page").toIntOrNull() ?: 1 }
        val template = pages.last()!!.attr("href").substringAfter('?', "").replace(Regex("\\d+$"), "")
        if (template.isEmpty()) return first
        val builder = StringBuilder(first)
        for (page in 2..maxPage) {
            val html = client.newCall(POST("$ajax?$template$page", ajaxHeaders)).awaitSuccess().body.string()
            if (html.isNotBlank() && html != "0") builder.append(html)
        }
        return builder.toString()
    }

    // Text

    override fun chapterTextParse(response: Response): String {
        val document = response.asJsoup().checkBlocked(response, baseUrl)
        val content = document.selectFirst(".text-left, .text-right, .entry-content, .reading-content")
            ?: document.selectFirst(".c-blog-post > div > div:nth-child(2)")
            ?: throw Exception("No chapter text found")
        content.select("script, style, ins, noscript, iframe, .code-block, .adsbygoogle, .readaloud-widget").remove()
        cleanChapterText(content, response.request.url.toString())
        content.select("img").forEach { img -> img.imageUrl()?.let { img.attr("src", it) } }
        return content.html()
    }

    /** Site-specific cleanup of a chapter's content, after the theme's own. [url] is the chapter page's. */
    protected open fun cleanChapterText(content: Element, url: String) = Unit

    // Helpers

    private fun Element.wholeTextParagraphs(): String? {
        val paragraphs = select("p").map { it.text().trim() }.filter { it.isNotEmpty() }
        val text = if (paragraphs.isNotEmpty()) paragraphs.joinToString("\n\n") else text().trim()
        return text.ifEmpty { null }
    }

    private fun parseStatus(text: String): Int = when {
        ONGOING.any { text.contains(it, ignoreCase = true) } -> SManga.ONGOING
        COMPLETED.any { text.contains(it, ignoreCase = true) } -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    protected open fun parseDate(text: String): Long = WpCommon.parseDate(text, Locale.forLanguageTag(lang))

    companion object {
        private const val PAGE_SIZE_HINT = 10

        private val GENRE_LABELS = setOf(
            "Genre(s)", "Genre", "Tags(s)", "Tag(s)", "Tags", "Género(s)", "Kategori", "التصنيفات",
        )
        private val AUTHOR_LABELS = setOf("Author(s)", "Author", "Autor(es)", "المؤلف", "المؤلف (ين)")
        private val STATUS_LABELS = setOf("Status", "Novel", "Estado", "Durum")
        private val ONGOING = listOf("OnGoing", "Ongoing", "مستمرة", "En curso", "Devam", "Berlangsung")
        private val COMPLETED = listOf("Completed", "Complete", "مكتملة", "Completado", "Tamamlandı", "Tamat")
    }
}
