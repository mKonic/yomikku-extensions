package app.yomikku.extension.en.leafstudio

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

/** LeafStudio. Ported from LNReader's leafstudio plugin. */
class LeafStudio : HttpSource() {

    override val name = "LeafStudio"
    override val baseUrl = "https://leafstudio.site"
    override val lang = "en"
    override val supportsLatest = false

    // Listings

    private fun novelsUrl(page: Int) = baseUrl + "/novels" + if (page > 1) "/page/$page" else ""

    override fun popularMangaRequest(page: Int) = GET(novelsUrl(page), headers)

    override fun popularMangaParse(response: Response) = novelsParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = novelsUrl(page).toHttpUrl().newBuilder().addQueryParameter("search", query.trim()).build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = novelsParse(response)

    private fun novelsParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val novels = document.select("a.novel-item").map { item ->
            SManga.create().apply {
                setUrlWithoutDomain(item.absUrl("href"))
                title = item.selectFirst("p.novel-item-title")?.text()?.trim().orEmpty()
                thumbnail_url = item.selectFirst("img")?.absUrl("src")
            }
        }
        val page = PAGE.find(response.request.url.encodedPath)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val hasNext = document.selectFirst("a[href*=/novels/page/${page + 1}]") != null
        return MangasPage(novels, hasNext)
    }

    // Details

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h1.title")?.text()?.trim().orEmpty()
            thumbnail_url = document.selectFirst("img#novel_cover")?.absUrl("src")
            description = document.select("div.desc_div > p").map { it.text().trim() }.filter { it.isNotEmpty() }
                .joinToString("\n\n").ifEmpty { null }
            genre = document.select("div#tags_div > a.novel_genre").joinToString { it.text().trim() }.ifEmpty { null }
            status = when (document.selectFirst("a#novel_status")?.text()?.trim()) {
                "Active" -> SManga.ONGOING
                "Completed" -> SManga.COMPLETED
                "Hiatus" -> SManga.ON_HIATUS
                "Dropped" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
        }
    }

    // Chapters

    override fun chapterListParse(response: Response): List<SChapter> {
        // Newest first; premium chapters cost the site's currency.
        return response.asJsoup().select("a.chap[href]").map { link ->
            val locked = link.hasClass("premium_chap")
            val title = (link.selectFirst("p")?.text() ?: link.ownText()).trim()
            SChapter.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                name = (if (locked) "🔒 " else "") + title
                chapter_number = CHAPTER_NUMBER.find(title)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
            }
        }
    }

    // Text

    override fun chapterTextParse(response: Response): String {
        // Only the chapter's own paragraphs: the article also holds filler divs meant to confuse scrapers.
        val paragraphs = response.asJsoup().select("article > p.chapter_content")
        if (paragraphs.isEmpty()) throw Exception("No chapter text found. Premium chapters need a purchase on the site.")
        return paragraphs.joinToString("") { "<p>${it.html()}</p>" }
    }

    companion object {
        private val PAGE = Regex("""/page/(\d+)""")
        private val CHAPTER_NUMBER = Regex("""(?i)chapter\s*(\d+(?:\.\d+)?)""")
    }
}
