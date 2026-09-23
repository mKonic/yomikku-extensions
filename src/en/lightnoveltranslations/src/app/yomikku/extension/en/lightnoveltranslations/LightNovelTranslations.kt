package app.yomikku.extension.en.lightnoveltranslations

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response

/** Light Novel Translations. Ported from LNReader's lightnoveltranslation plugin. */
class LightNovelTranslations : HttpSource() {

    override val name = "Light Novel Translations"
    override val baseUrl = "https://lightnovelstranslations.com"
    override val lang = "en"
    override val supportsLatest = true

    // Listings

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/read/page/$page/?sortby=most-liked", headers)

    override fun popularMangaParse(response: Response) = novelsParse(response)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/read/page/$page/?sortby=most-recent", headers)

    override fun latestUpdatesParse(response: Response) = novelsParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        POST("$baseUrl/read/", headers, FormBody.Builder().add("field-search", query.trim()).build())

    override fun searchMangaParse(response: Response) = novelsParse(response).let { MangasPage(it.mangas, false) }

    private fun novelsParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val novels = document.select("div.read_list-story-item").mapNotNull { item ->
            val link = item.selectFirst(".item_thumb a[href]") ?: return@mapNotNull null
            SManga.create().apply {
                url = link.absUrl("href").removePrefix(baseUrl).substringBefore('?')
                title = link.attr("title").ifBlank { link.text() }.trim()
                thumbnail_url = item.selectFirst(".item_thumb img")?.absUrl("src")
            }
        }
        val page = PAGE.find(response.request.url.encodedPath)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val hasNext = document.selectFirst("a[href*=/read/page/${page + 1}/]") != null
        return MangasPage(novels, hasNext)
    }

    // Details

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("div.novel_title h3")?.text()?.trim().orEmpty()
            thumbnail_url = document.selectFirst("div.novel-image img")?.absUrl("src")
            author = document.select("div.novel_detail_info li").map { it.text() }
                .firstOrNull { it.startsWith("Author") }?.substringAfter(':')?.trim()
            description = document.select("div.novel_text p").map { it.text().trim() }.filter { it.isNotEmpty() }
                .joinToString("\n\n").ifEmpty { null }
            status = when (document.selectFirst("div.novel_status")?.text()?.trim()) {
                "Ongoing" -> SManga.ONGOING
                "Hiatus" -> SManga.ON_HIATUS
                "Completed" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    // Chapters

    override fun chapterListRequest(manga: SManga) = GET("$baseUrl${manga.url}?tab=table_contents", headers)

    override fun chapterListParse(response: Response): List<SChapter> =
        response.asJsoup().select("li.chapter-item").mapNotNull { item ->
            val link = item.selectFirst("a[href]") ?: return@mapNotNull null
            val locked = item.hasClass("lock")
            SChapter.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                name = (if (locked) "🔒 " else "") + link.text().trim()
                chapter_number = CHAPTER_NUMBER.find(link.text())?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
            }
        }.reversed()

    // Text

    override fun chapterTextParse(response: Response): String {
        val content = response.asJsoup().selectFirst("div.text_story")
            ?: throw Exception("No chapter text found. Locked chapters need a subscription on the site.")
        content.select("div.ads_content, script, ins").remove()
        return content.html()
    }

    companion object {
        private val PAGE = Regex("""/page/(\d+)""")
        private val CHAPTER_NUMBER = Regex("""(?i)chapter\s*(\d+(?:\.\d+)?)""")
    }
}
