package app.yomikku.extension.en.novelhall

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

/** Novel Hall. Ported from LNReader's novelhall plugin. */
class NovelHall : HttpSource() {

    override val name = "Novel Hall"
    override val baseUrl = "https://www.novelhall.com"
    override val lang = "en"
    override val supportsLatest = false

    // Listings: the site's lists carry neither covers nor untruncated titles; details fill both in.

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/all2022-$page.html", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val novels = document.select("li.btm a[href]").map { link ->
            SManga.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                title = link.text().trim()
            }
        }
        val page = PAGE.find(response.request.url.encodedPath)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val hasNext = document.selectFirst("a[href*=all2022-${page + 1}.html]") != null
        return MangasPage(novels, hasNext)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/index.php".toHttpUrl().newBuilder()
            .addQueryParameter("s", "so")
            .addQueryParameter("module", "book")
            .addQueryParameter("keyword", query.trim())
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val novels = response.asJsoup().select("table tr td:nth-child(2) a[href]").map { link ->
            SManga.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                title = link.text().replace(WHITESPACE, " ").trim()
            }
        }
        return MangasPage(novels, false)
    }

    // Details

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst(".book-info > h1")?.text()?.trim().orEmpty()
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            // The summary is there twice: cut short, and whole but hidden.
            description = (document.selectFirst(".intro .js-close-wrap") ?: document.selectFirst(".intro"))
                ?.apply { select("span.blue, img").remove() }?.text()?.trim()
            val info = document.selectFirst(".total")?.apply { select("p").remove() }
            info?.select("span")?.forEach { span ->
                val text = span.text()
                when {
                    text.startsWith("Author") -> author = text.substringAfter('：').trim()
                    text.startsWith("Status") -> status = when (text.substringAfter('：').trim()) {
                        "Active" -> SManga.ONGOING
                        "Completed" -> SManga.COMPLETED
                        else -> SManga.UNKNOWN
                    }
                }
            }
            genre = info?.select("a")?.joinToString { it.text().trim() }?.ifEmpty { null }
        }
    }

    // Chapters

    override fun chapterListParse(response: Response): List<SChapter> {
        val chapters = response.asJsoup().select("#morelist ul > li a[href]").map { link ->
            SChapter.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                name = link.text().trim()
                chapter_number = CHAPTER_NUMBER.find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
            }
        }
        // The site lists a few recent chapters ahead of chapter 1, so order by number when every chapter has one.
        return if (chapters.all { it.chapter_number >= 0 }) {
            chapters.sortedByDescending { it.chapter_number }
        } else {
            chapters.reversed()
        }
    }

    // Text

    override fun chapterTextParse(response: Response): String {
        val content = response.asJsoup().selectFirst("#htmlContent") ?: throw Exception("No chapter text found")
        content.select("script, ins, .adsbygoogle").remove()
        return content.html()
    }

    companion object {
        private val PAGE = Regex("""all2022-(\d+)\.html""")
        private val WHITESPACE = Regex("""\s+""")
        private val CHAPTER_NUMBER = Regex("""(?i)^chapter\s*(\d+(?:\.\d+)?)""")
    }
}
