package app.yomikku.extension.en.hangulplanet

import app.yomikku.lib.paced.Paced
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * HangulPlanet, Korean web novels on a Next.js site of its own. Ported from LNReader's HangulPlanet plugin. The whole
 * catalogue fits on the browse page; a novel's free chapters come about 47 to a page, newest first.
 */
class HangulPlanet : HttpSource() {

    override val name = "HangulPlanet"
    override val baseUrl = "https://hangulplanet.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    // Listings

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/browse?sort=popular", headers)

    override fun popularMangaParse(response: Response) = browseParse(response)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/browse?sort=latest", headers)

    override fun latestUpdatesParse(response: Response) = browseParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) =
        GET("$baseUrl/browse".toHttpUrl().newBuilder().addQueryParameter("q", query.trim()).build(), headers)

    override fun searchMangaParse(response: Response) = browseParse(response)

    private fun browseParse(response: Response): MangasPage {
        val novels = response.asJsoup().select("a[href^=/novel/]:has(img)").mapNotNull { card ->
            val image = card.selectFirst("img") ?: return@mapNotNull null
            SManga.create().apply {
                url = card.attr("href").substringBefore('?')
                title = image.attr("alt").trim()
                thumbnail_url = image.coverUrl()
            }
        }.filter { it.title.isNotEmpty() && "/chapter-" !in it.url }.distinctBy { it.url }
        return MangasPage(novels, false)
    }

    /** Covers go through Next's image proxy; its `url` parameter is the original on the site's CDN. */
    private fun Element.coverUrl(): String? {
        val src = absUrl("src").ifEmpty { return null }
        return src.toHttpUrlOrNull()?.queryParameter("url") ?: src
    }

    // Details

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            val heading = document.selectFirst("h1")
            title = heading?.text()?.trim().orEmpty()
            author = heading?.nextElementSibling()?.takeIf { it.tagName() == "p" }?.text()?.trim()?.ifEmpty { null }
            thumbnail_url = document.selectFirst("img.object-cover")?.coverUrl()
            description = document.selectFirst(".prose")?.select("p")?.map { it.text().trim() }
                ?.filter { it.isNotEmpty() }?.joinToString("\n\n")?.ifEmpty { null }
            genre = document.select("a[href*=browse?genre=]").map { it.text().trim() }.filter { it.isNotEmpty() }
                .distinct().joinToString().ifEmpty { null }
            val state = document.selectFirst("span[data-slot=badge]")?.text().orEmpty()
            status = when {
                "Ongoing" in state -> SManga.ONGOING
                "Completed" in state -> SManga.COMPLETED
                "Hiatus" in state -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }

    // Chapters: the first page names the last one, and the rest are fetched a few at a time.

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val first = page(manga.url, 1)
        val lastPage = first.select("a[href*=cpage=]").mapNotNull { CPAGE.find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 1
        val permits = Semaphore(PARALLEL_PAGES)
        val rest = coroutineScope {
            (2..lastPage).map { number -> async { permits.withPermit { page(manga.url, number) } } }.awaitAll()
        }
        return (listOf(first) + rest).flatMap(::chaptersOf).distinctBy { it.url }
            .sortedByDescending { it.chapter_number }
    }

    private suspend fun page(novelUrl: String, number: Int): Document {
        val url = if (number == 1) baseUrl + novelUrl else "$baseUrl$novelUrl?cpage=$number"
        return Paced.fetch(client, GET(url, headers)).use { it.asJsoup() }
    }

    // Most rows are streamed in after the list, in hidden blocks at the end of the page, so look through all of it.
    private fun chaptersOf(document: Document) = document.select("a[href*=/chapter-]:has(.line-clamp-1)").mapNotNull { link ->
        val number = CHAPTER_NUMBER.find(link.attr("href"))?.groupValues?.get(1)?.toFloatOrNull() ?: return@mapNotNull null
        SChapter.create().apply {
            url = link.attr("href").substringBefore('?')
            val title = link.selectFirst(".line-clamp-1")?.text()?.trim().orEmpty()
            name = "Chapter ${number.toInt()}" + if (title.isEmpty()) "" else ": $title"
            chapter_number = number
            date_upload = link.selectFirst("time[datetime]")?.attr("datetime")
                ?.let { runCatching { DATE_FORMAT.parse(it.take(19))?.time }.getOrNull() } ?: 0L
        }
    }

    // Text

    override fun chapterTextParse(response: Response): String {
        val content = response.asJsoup().selectFirst("article[data-reader-article=true] .reader-prose")
            ?: throw Exception("No chapter text found")
        // The translator/editor credit opens most chapters.
        content.selectFirst("p")?.takeIf { CREDIT.containsMatchIn(it.text()) }?.remove()
        return content.html()
    }

    companion object {
        private const val PARALLEL_PAGES = 4
        private val CPAGE = Regex("""cpage=(\d+)""")
        private val CHAPTER_NUMBER = Regex("""/chapter-(\d+)$""")
        private val CREDIT = Regex("""^\s*(TL|ED|TL/ED)\s*[:/]""")
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
