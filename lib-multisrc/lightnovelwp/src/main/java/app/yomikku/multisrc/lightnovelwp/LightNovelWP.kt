package app.yomikku.multisrc.lightnovelwp

import app.yomikku.lib.lnfilters.LnFilters
import app.yomikku.lib.wpcommon.WpCommon
import app.yomikku.lib.wpcommon.WpCommon.checkBlocked
import app.yomikku.lib.wpcommon.WpCommon.imageUrl
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
import org.jsoup.nodes.Element
import java.util.Locale

/**
 * Sites built on the LightNovel WordPress theme (themesia). Ported from LNReader's lightnovelwp multisrc plugin.
 *
 * @param seriesPath where the site lists its series, when not at `/series/`.
 * @param reverseChapters whether the site lists chapters newest first. LNReader names the option after what it does to
 * the list; here it means the list already has the order the app wants.
 */
abstract class LightNovelWP(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
    private val seriesPath: String = "series",
    private val reverseChapters: Boolean = false,
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

    private fun seriesRequest(page: Int, filters: FilterList, order: String?): Request {
        val url = "$baseUrl/${seriesPath.trim('/')}/".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
        if (order != null) url.addQueryParameter("order", order)
        LnFilters.queryParams(filters)
            .filterNot { (key, _) -> key == "order" && order != null }
            .forEach { (key, value) -> url.addQueryParameter(key, value) }
        return GET(url.build(), headers)
    }

    override fun popularMangaRequest(page: Int) = seriesRequest(page, FilterList(), "popular")

    override fun popularMangaParse(response: Response) = novelsParse(response)

    override fun latestUpdatesRequest(page: Int) = seriesRequest(page, FilterList(), "latest")

    override fun latestUpdatesParse(response: Response) = novelsParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isBlank()) return seriesRequest(page, filters, order = null)
        val url = "$baseUrl/page/$page/".toHttpUrl().newBuilder().addQueryParameter("s", query).build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = novelsParse(response)

    protected open fun novelsParse(response: Response): MangasPage {
        val document = response.asJsoup().checkBlocked(response, baseUrl)
        val novels = document.select("article").mapNotNull { article ->
            val link = article.selectFirst("a[href][title]") ?: return@mapNotNull null
            SManga.create().apply {
                title = link.attr("title").trim()
                setUrlWithoutDomain(link.absUrl("href"))
                thumbnail_url = article.selectFirst("img")?.imageUrl()
            }
        }.distinctBy { it.url }
        val hasNext = document.selectFirst(".hpage a.r, .pagination a.next, a.next.page-numbers") != null
        return MangasPage(novels, hasNext)
    }

    // Details

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup().checkBlocked(response, baseUrl)
        return SManga.create().apply {
            val cover = document.selectFirst("img.ts-post-image")
            title = document.selectFirst("h1.entry-title")?.text()?.trim()
                ?: cover?.attr("title")?.trim().orEmpty()
            thumbnail_url = cover?.imageUrl()
            genre = document.select(".genxed a, .sertogenre a").joinToString { it.text().trim() }.ifEmpty { null }
            description = document.selectFirst("[itemprop=description], .entry-content")?.let { summary ->
                summary.select("script, style, .code-block").remove()
                summary.select("p").map { it.text().trim() }.filter { it.isNotEmpty() }
                    .ifEmpty { listOf(summary.text().trim()) }
                    .joinToString("\n\n")
            }

            // "<span><b>Status:</b> Completed</span>", or the newer "sertoinfo" table.
            document.select(".spe span, .serl, .sertoauth .serval, .sertostat").forEach { span ->
                val label = span.selectFirst("b, .sername")?.text().orEmpty()
                    .lowercase().removeSuffix(":").trim()
                val value = span.clone().apply { select("b, .sername").remove() }.text().trim()
                when (label) {
                    in AUTHOR_LABELS -> author = value
                    in ARTIST_LABELS -> artist = value
                    in STATUS_LABELS -> status = parseStatus(value)
                }
            }
            document.selectFirst(".sertostat")?.let { status = parseStatus(it.text()) }
        }
    }

    // Chapters

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup().checkBlocked(response, baseUrl)
        val novelTitle = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val chapters = document.select(".eplister li").mapNotNull { item ->
            val link = item.selectFirst("a[href]") ?: return@mapNotNull null
            val num = item.selectFirst(".epl-num")?.text()?.trim().orEmpty()
            val title = item.selectFirst(".epl-title")?.text()?.trim().orEmpty()
                .removePrefix(novelTitle).trim()
            val price = item.selectFirst(".epl-price")?.text()?.trim()?.lowercase().orEmpty()
            val locked = "🔒" in num || price !in FREE_PRICES
            SChapter.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                name = (if (locked) "🔒 " else "") + title.ifEmpty { num.replace("🔒", "").trim() }
                chapter_number = CHAPTER_NUMBER.find(num.ifEmpty { title })?.value?.toFloatOrNull() ?: -1f
                date_upload = item.selectFirst(".epl-date")?.text()?.let(::parseDate) ?: 0L
            }
        }
        return if (reverseChapters) chapters else chapters.reversed()
    }

    // Text

    override fun chapterTextParse(response: Response): String {
        val document = response.asJsoup().checkBlocked(response, baseUrl)
        // Some sites put an announcement in its own .epcontent before the chapter.
        val content = document.select(".epcontent").lastOrNull()
            ?: throw Exception("No chapter text found")
        content.select(JUNK).remove()
        cleanChapterText(content, response.request.url.toString())
        content.select("img").forEach { img -> img.imageUrl()?.let { img.attr("src", it) } }
        return content.html()
    }

    /** Site-specific cleanup of a chapter's content, after the theme's own. [url] is the chapter page's. */
    protected open fun cleanChapterText(content: Element, url: String) = Unit

    // Helpers

    private fun parseStatus(text: String): Int {
        val value = text.lowercase().substringAfter(':').trim()
        return when {
            ONGOING.any { it in value } -> SManga.ONGOING
            COMPLETED.any { it in value } -> SManga.COMPLETED
            HIATUS.any { it in value } -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }

    protected open fun parseDate(text: String): Long = WpCommon.parseDate(text, Locale.forLanguageTag(lang))

    companion object {
        private val AUTHOR_LABELS = setOf("author", "الكاتب", "auteur", "autor", "yazar", "penulis")
        private val ARTIST_LABELS = setOf("artist", "الفنان", "artiste", "artista", "çizer")
        private val STATUS_LABELS = setOf("status", "الحالة", "statut", "estado", "durum")
        private val ONGOING = listOf("ongoing", "مستمرة", "en cours", "em andamento", "en progreso", "devam ediyor")
        private val COMPLETED = listOf("completed", "مكتملة", "complété", "completo", "completado", "tamamlandı", "tamat")
        private val HIATUS = listOf("hiatus", "متوقفة", "en pause", "hiato", "pausa", "pausado", "duraklatıldı")
        private val FREE_PRICES = setOf("", "free", "gratuit", "مجاني", "livre", "gratis")
        private val CHAPTER_NUMBER = Regex("\\d+(\\.\\d+)?(?=\\D*$)")

        /** Scripts, ads and share widgets the theme and its plugins put inside the chapter. */
        private const val JUNK = "script, style, ins, noscript, iframe, .kln, .code-block, .adsbygoogle, " +
            ".sharedaddy, .wp-block-buttons, a[rel~=sponsored]"
    }
}
