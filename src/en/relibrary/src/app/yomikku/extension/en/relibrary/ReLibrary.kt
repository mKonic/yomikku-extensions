package app.yomikku.extension.en.relibrary

import app.yomikku.lib.wpcommon.WpCommon.imageUrl
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element

/**
 * Re:Library. Ported from LNReader's relibrary plugin, with the series page rewritten for the site's new info table.
 * The site has no search, so search filters its one-page index of every translation by title.
 */
class ReLibrary : HttpSource() {

    override val name = "Re:Library"
    override val baseUrl = "https://re-library.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder().add("Referer", "$baseUrl/")

    // Listings

    // The most-popular page is a single page of cards.
    override fun popularMangaRequest(page: Int) = GET("$baseUrl/translations/most-popular/", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val novels = response.asJsoup().select(".relibrary-top-novels .relibrary-novel-card").mapNotNull { card ->
            card.selectFirst(".relibrary-title a")?.let { novel(it, card.selectFirst("img.relibrary-cover")) }
        }
        return MangasPage(novels, false)
    }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/tag/translations/page/$page/", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val novels = document.select("article.type-page.page").mapNotNull { article ->
            // The title heading also wraps the novel card, so only read its own link.
            article.selectFirst(".entry-title > a")?.let { novel(it, article.selectFirst("img.relibrary-cover")) }
        }
        return MangasPage(novels, document.selectFirst(".nav-links a.next, a.next.page-numbers") != null)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/translations/#${query.trim()}", headers)

    override fun searchMangaParse(response: Response): MangasPage {
        val query = response.request.url.fragment.orEmpty().lowercase()
        val novels = response.asJsoup().select("article article").mapNotNull { article ->
            val link = article.selectFirst("a[href]") ?: return@mapNotNull null
            val title = article.selectFirst("h4")?.text()?.trim().orEmpty()
            if (title.isEmpty() || query !in title.lowercase()) return@mapNotNull null
            novel(link, article.selectFirst("img"), title)
        }
        return MangasPage(novels, false)
    }

    private fun novel(link: Element, cover: Element?, title: String = link.text().trim()) = SManga.create().apply {
        url = link.absUrl("href").toHttpUrl().encodedPath
        this.title = title
        // Series without art carry the site's no-image.png.
        thumbnail_url = cover?.imageUrl()?.takeUnless { "/no-image." in it }
    }

    // Details

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val info = document.selectFirst(".entry-content > table")
        // Author, translator and language rows of the table beside the cover.
        val rows = info?.select("table tr")?.associate { row ->
            row.select("td").let { it.getOrNull(0)?.text()?.trim().orEmpty() to it.getOrNull(1)?.text()?.trim().orEmpty() }
        }.orEmpty()
        return SManga.create().apply {
            title = document.selectFirst("header.entry-header > .entry-title")?.text()?.trim().orEmpty()
            thumbnail_url = info?.selectFirst("img")?.imageUrl()
            author = rows["Author"]?.ifEmpty { null }
            genre = info?.select("a[href*=/tag/]")?.joinToString { it.text().trim() }
            description = document.selectFirst(".entry-content > .su-box .su-box-content")?.select("p")
                ?.map { it.text().trim() }?.filter { it.isNotEmpty() }?.joinToString("\n\n")
            // The site doesn't publish a status.
            status = SManga.UNKNOWN
        }
    }

    // Chapters

    override fun chapterListParse(response: Response): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        // One accordion of volume spoilers, and another of side posts ("Forums") that aren't part of the story.
        for (spoiler in response.asJsoup().select(".entry-content > .su-accordion .su-spoiler")) {
            val volume = spoiler.selectFirst(".su-spoiler-title")?.text()?.trim().orEmpty()
            if (volume.equals("Forums", ignoreCase = true)) continue
            for (link in spoiler.select("li > a[href]")) {
                chapters += SChapter.create().apply {
                    url = link.absUrl("href").toHttpUrl().encodedPath
                    name = if (volume.isEmpty()) link.text().trim() else "$volume · ${link.text().trim()}"
                    chapter_number = (chapters.size + 1).toFloat()
                }
            }
        }
        return chapters.reversed()
    }

    // Text

    override fun chapterTextParse(response: Response): String {
        val content = response.asJsoup().selectFirst(".entry-content") ?: throw Exception("No chapter text found")
        // Early access chapters only show a password form until they unlock.
        content.selectFirst("form.post-password-form")?.let { form ->
            val unlock = form.selectFirst(".ppw-pcp-pf-desc-below-form")?.text()?.trim().orEmpty()
            throw Exception("This chapter is password protected (early access) on Re:Library. $unlock".trim())
        }
        // The text sits between the top and bottom navigation, each a pair of .PageLink divs between <hr>s.
        val links = content.children().filter { it.hasClass("PageLink") }
        val top = links.firstOrNull()?.nextElementSiblings()?.firstOrNull { it.tagName() == "hr" }
        val bottom = links.lastOrNull()?.previousElementSiblings()?.firstOrNull { it.tagName() == "hr" }
        if (top != null && bottom != null && top.siblingIndex() < bottom.siblingIndex()) {
            top.previousElementSiblings().remove()
            bottom.nextElementSiblings().remove()
            // The credits table stands on its own, or opens a wrapper (div.ppw-restricted-content) holding the chapter.
            top.nextElementSibling()?.let { next ->
                (if (next.tagName() == "table") next else next.firstElementChild()?.takeIf { it.tagName() == "table" })?.remove()
            }
            top.remove()
            bottom.remove()
        }
        content.select("script, style, noscript, .code-block, .post-views, .wpulike, .sharedaddy").remove()
        return content.html().trim().ifEmpty { throw Exception("No chapter text found") }
    }
}
