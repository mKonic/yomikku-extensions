package app.yomikku.extension.en.puffinfolio

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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Puffin Folio. Ported from LNReader's puffinFolio plugin. The whole catalogue sits on one browse page. */
class PuffinFolio : HttpSource() {

    override val name = "Puffin Folio"
    override val baseUrl = "https://www.puffinfolio.com"
    override val lang = "en"
    override val supportsLatest = false

    // Listings

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/browse", headers)

    override fun popularMangaParse(response: Response) = MangasPage(novels(response), false)

    // Search filters the one catalogue page by title.
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val all = getPopularManga(1).mangas
        return MangasPage(all.filter { it.title.contains(query.trim(), ignoreCase = true) }, false)
    }

    private fun novels(response: Response): List<SManga> =
        response.asJsoup().select("a[href^=/novel/]").filterNot { "/chapter/" in it.attr("href") }.mapNotNull { link ->
            val image = link.selectFirst("img")
            val title = link.selectFirst("h3")?.text()?.trim() ?: image?.attr("alt")?.trim()
            if (title.isNullOrEmpty()) return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                this.title = title
                thumbnail_url = image?.let(::coverUrl)
            }
        }.distinctBy { it.url }

    /** Covers go through next/image; the file itself is in its url parameter. */
    private fun coverUrl(image: Element): String? {
        val source = image.absUrl("src").ifEmpty { image.attr("srcset").substringBefore(' ') }.ifEmpty { return null }
        val file = source.toHttpUrlOrNull()?.queryParameter("url") ?: return source
        return if (file.startsWith("http")) file else baseUrl + "/" + file.trimStart('/')
    }

    // Details

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = document.selectFirst("img[src*=covers], img[srcset*=covers]")?.let(::coverUrl)
            description = document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            genre = document.select("a[href^='/browse?tag=']").joinToString { it.text().trim() }.ifEmpty { null }
            status = when (document.select("span").map { it.text().trim() }.firstOrNull { it == "Ongoing" || it == "Completed" }) {
                "Ongoing" -> SManga.ONGOING
                "Completed" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    // Chapters

    override fun chapterListParse(response: Response): List<SChapter> {
        val chapters = response.asJsoup().select("li a[href*=/chapter/]").map { link ->
            // The label starts with a badge holding the chapter's number.
            val label = link.selectFirst("span")?.clone()?.apply { select("span").remove() }
            val time = link.selectFirst("time")
            SChapter.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                name = label?.text()?.trim()?.ifEmpty { null } ?: link.attr("href").substringAfterLast('/')
                chapter_number = CHAPTER_NUMBER.find(link.attr("href"))?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
                date_upload = time?.attr("datetime")?.let { runCatching { DATE_FORMAT.parse(it)?.time }.getOrNull() } ?: 0L
            }
        }.distinctBy { it.url }
        return chapters.sortedByDescending { it.chapter_number }
    }

    // Text

    override fun chapterTextParse(response: Response): String =
        response.asJsoup().selectFirst(".reader-prose")?.html() ?: throw Exception("No chapter text found")

    companion object {
        private val CHAPTER_NUMBER = Regex("""/chapter/(\d+)""")
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
