package app.yomikku.extension.en.faqwiki

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response
import org.jsoup.nodes.Element

/** Faq Wiki. Ported from LNReader's faqwikius plugin. The home page lists every novel. */
class FaqWiki : HttpSource() {

    override val name = "Faq Wiki"
    override val baseUrl = "https://faqwiki.xyz"
    override val lang = "en"
    override val supportsLatest = false

    // Listings

    override fun popularMangaRequest(page: Int) = GET(baseUrl, headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val novels = response.asJsoup().select(".plt-page-item").mapNotNull { item ->
            val link = item.selectFirst("a[href]") ?: return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                title = cleanTitle(item.text())
                thumbnail_url = item.selectFirst("img")?.let(::imageUrl)
            }
        }.distinctBy { it.url }
        return MangasPage(novels, false)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val all = getPopularManga(1).mangas
        return MangasPage(all.filter { it.title.contains(query.trim(), ignoreCase = true) }, false)
    }

    private fun cleanTitle(text: String) = text.replace(TITLE_SUFFIX, "").trim()

    private fun imageUrl(image: Element) = image.absUrl("data-ezsrc").ifEmpty { image.absUrl("src") }
        .substringBefore("?ezimgfmt=").ifEmpty { null }

    // Details

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        document.select("script").remove()
        return SManga.create().apply {
            title = cleanTitle(document.selectFirst(".entry-title")?.text().orEmpty())
            thumbnail_url = document.selectFirst(".wp-block-image img")?.let(::imageUrl)
            // "Description:", "Author(s):" and "Genre:" labels, where a novel has them.
            document.select(".entry-content p:has(strong)").forEach { paragraph ->
                val label = paragraph.selectFirst("strong")!!.text().trim().lowercase()
                val value = paragraph.text().removePrefix(paragraph.selectFirst("strong")!!.text()).trim()
                when (label) {
                    "description:" -> description = (listOf(value) + paragraph.nextElementSiblings()
                        .takeWhile { it.selectFirst("strong") == null && it.normalName() == "p" }
                        .map { it.text().trim() }).filter { it.isNotEmpty() }.joinToString("\n\n")
                    "author(s):" -> author = value
                    "genre:" -> genre = value
                }
            }
            status = if (document.select(".entry-content").text().contains("completed", ignoreCase = true)) {
                SManga.COMPLETED
            } else {
                SManga.ONGOING
            }
        }
    }

    // Chapters

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val novel = cleanTitle(document.selectFirst(".entry-title")?.text().orEmpty())
        return document.select(".lcp_catlist li a[href]").mapIndexed { index, link ->
            SChapter.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                name = link.text().replace(novel, "").replace("Novel", "").trim().ifEmpty { link.text().trim() }
                chapter_number = index + 1f
            }
        }.reversed()
    }

    // Text

    override fun chapterTextParse(response: Response): String {
        val content = response.asJsoup().selectFirst(".entry-content") ?: throw Exception("No chapter text found")
        // Text resizing, sharing, translation, ads and navigation are all divs and spans; the chapter is paragraphs.
        content.select("div, span, script, center, a.button").remove()
        content.select("p").filter { it.text().startsWith("NOTICE:") }.forEach { it.remove() }
        return content.html()
    }

    companion object {
        private val TITLE_SUFFIX = Regex("""\s*Novel\s*[–-]\s*All Chapters\s*$""")
    }
}
