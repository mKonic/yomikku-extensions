package app.yomikku.extension.en.skythewood

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import org.jsoup.nodes.Element

/**
 * Skythewood Translations, a Blogger site. Ported from LNReader's skythewood plugin. Its finished projects are listed
 * on one page, each project page links its chapters under volume headings.
 */
class Skythewood : HttpSource() {

    override val name = "Skythewood Translations"
    override val baseUrl = "https://skythewood.blogspot.com"
    override val lang = "en"
    override val supportsLatest = false

    /** The path of a link to this blog, which also appears under its .sg and http addresses; null for other sites. */
    private fun blogPath(link: Element): String? {
        val url = link.absUrl("href").toHttpUrlOrNull() ?: return null
        if (!url.host.startsWith("skythewood.blogspot.")) return null
        return url.encodedPath
    }

    // Listings

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/p/done.html", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val body = response.asJsoup().selectFirst(".post-body") ?: return MangasPage(emptyList(), false)
        // A project's cover is the last image before its link; a project is linked twice, in English and Japanese.
        var cover: String? = null
        val novels = mutableListOf<SManga>()
        for (element in body.select("*")) {
            if (element.normalName() == "img") cover = element.absUrl("src")
            if (element.normalName() != "a" || element.text().isBlank()) continue
            val path = blogPath(element)?.takeIf { it.startsWith("/p/") && it != "/p/projects.html" } ?: continue
            if (novels.any { it.url == path }) continue
            novels += SManga.create().apply {
                url = path
                title = element.text().trim()
                thumbnail_url = cover
            }
        }
        return MangasPage(novels, false)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val all = getPopularManga(1).mangas
        return MangasPage(all.filter { it.title.contains(query.trim(), ignoreCase = true) }, false)
    }

    // Details

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst(".post-title")?.text()?.trim().orEmpty()
            thumbnail_url = document.select(".post-body img").firstOrNull()?.absUrl("src")
            author = document.select(".post-body b").map { it.text() }.firstOrNull { it.startsWith("Author") }
                ?.substringAfter(':')?.trim()
            status = SManga.COMPLETED
        }
    }

    // Chapters

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.asJsoup().selectFirst(".post-body") ?: return emptyList()
        var volume: String? = null
        val chapters = mutableListOf<SChapter>()
        for (element in body.select("*")) {
            val text = element.ownText().trim()
            if (text.startsWith("Volume")) volume = text
            if (element.normalName() != "a" || element.text().isBlank()) continue
            val path = blogPath(element)?.takeIf { !it.startsWith("/p/") } ?: continue
            if (chapters.any { it.url == path }) continue
            chapters += SChapter.create().apply {
                url = path
                name = listOfNotNull(volume, element.text().trim()).joinToString(" - ")
                chapter_number = chapters.size + 1f
            }
        }
        return chapters.reversed()
    }

    // Text

    override fun chapterTextParse(response: Response): String {
        val content = response.asJsoup().selectFirst(".post-body") ?: throw Exception("No chapter text found")
        content.select("script, ins").remove()
        return content.html()
    }
}
