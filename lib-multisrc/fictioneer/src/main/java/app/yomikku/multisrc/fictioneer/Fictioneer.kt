package app.yomikku.multisrc.fictioneer

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
import org.jsoup.nodes.Document

/**
 * Sites built on the Fictioneer WordPress theme. Ported from LNReader's fictioneer multisrc plugin.
 *
 * @param browsePage path of the page listing every story.
 */
abstract class Fictioneer(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
    private val browsePage: String,
) : HttpSource() {

    override val supportsLatest = false

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    private fun paged(path: String, page: Int) =
        "$baseUrl/${path.trim('/')}/".replace(Regex("/+$"), "/") + if (page > 1) "page/$page/" else ""

    override fun popularMangaRequest(page: Int) = GET(paged(browsePage, page), headers)

    override fun popularMangaParse(response: Response) =
        novelsParse(response, "#featured-list > li > div > div, #list-of-stories > li > div > div")

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = paged("", page).toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .addQueryParameter("post_type", "fcn_story")
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = novelsParse(response, "#search-result-list > li > div > div")

    private fun novelsParse(response: Response, selector: String): MangasPage {
        val document = response.asJsoup().checkBlocked(response, baseUrl)
        val novels = document.select(selector).mapNotNull { card ->
            val link = card.selectFirst("h3 > a[href]") ?: return@mapNotNull null
            SManga.create().apply {
                title = link.text().trim()
                setUrlWithoutDomain(link.absUrl("href"))
                thumbnail_url = card.selectFirst("a.cell-img:has(img)")?.absUrl("href")
                    ?: card.selectFirst("img")?.imageUrl()
            }
        }
        val hasNext = document.selectFirst("a.next.page-numbers, .pagination .next") != null
        return MangasPage(novels, hasNext)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup().checkBlocked(response, baseUrl)
        return SManga.create().apply {
            title = document.selectFirst("h1.story__identity-title")?.text()?.trim().orEmpty()
            author = document.selectFirst("div.story__identity-meta")?.text()
                ?.substringBefore('|')?.removePrefix("Author: ")?.removePrefix("by ")?.trim()
            thumbnail_url = document.selectFirst("figure.story__thumbnail > a")?.absUrl("href")
            genre = document.select("div.tag-group > a, section.tag-group > a").joinToString { it.text().trim() }
            document.select("section.story__summary .related-stories-block").remove()
            description = document.selectFirst("section.story__summary")?.select("p")
                ?.map { it.text().trim() }?.filter { it.isNotEmpty() }?.joinToString("\n\n")
            status = when (document.selectFirst("span.story__status")?.text()?.trim()) {
                "Ongoing" -> SManga.ONGOING
                "Completed" -> SManga.COMPLETED
                "Cancelled" -> SManga.CANCELLED
                "Hiatus" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup().checkBlocked(response, baseUrl)
        // Password-protected and locked chapters can't be read without an account.
        return document.select("li.chapter-group__list-item._publish:not(._password)")
            .filterNot { it.selectFirst("i")?.hasClass("fa-lock") == true }
            .mapNotNull { item ->
                val link = item.selectFirst("a[href]") ?: return@mapNotNull null
                SChapter.create().apply {
                    setUrlWithoutDomain(link.absUrl("href"))
                    name = link.text().trim()
                }
            }
            .reversed()
    }

    override fun chapterTextParse(response: Response): String {
        val document = response.asJsoup().checkBlocked(response, baseUrl)
        transformChapter(document)
        val content = document.selectFirst("section#chapter-content > div") ?: throw Exception("No chapter text found")
        content.select("script, style, ins, noscript, iframe").remove()
        content.select("img").forEach { img -> img.imageUrl()?.let { img.attr("src", it) } }
        return content.html()
    }

    /** Site-specific decoding of the chapter page, before its content is taken. */
    protected open fun transformChapter(document: Document) = Unit
}
