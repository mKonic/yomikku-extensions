package app.yomikku.extension.en.peachpuff

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response

/**
 * Peach Puff Translations. Ported from LNReader's peachpuff plugin. The home page lists every novel; covers come from
 * the WordPress api, matching each novel page to the first image uploaded to it.
 */
class PeachPuff : HttpSource() {

    override val name = "Peach Puff Translations"
    override val baseUrl = "https://peachpuff.in"
    override val lang = "en"
    override val supportsLatest = false

    private val json = Json { ignoreUnknownKeys = true }

    // Listings

    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.newCall(GET(baseUrl, headers)).awaitSuccess().asJsoup()
        val covers = runCatching { covers() }.getOrDefault(emptyMap())
        val novels = document.select("ul.wp-block-list li a[title]").map { link ->
            SManga.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                title = link.attr("title").ifBlank { link.text() }.trim()
                thumbnail_url = covers[url.trimEnd('/')]
            }
        }.distinctBy { it.url }
        return MangasPage(novels, false)
    }

    private suspend fun covers(): Map<String, String> {
        val pages = client.newCall(GET("$baseUrl/wp-json/wp/v2/pages?per_page=100&_fields=id,link", headers))
            .awaitSuccess().use { json.decodeFromString<List<Page>>(it.body.string()) }
            .associate { it.id to it.link.toHttpUrl().encodedPath.trimEnd('/') }
        val media = client.newCall(GET("$baseUrl/wp-json/wp/v2/media?per_page=100&_fields=id,post,source_url", headers))
            .awaitSuccess().use { json.decodeFromString<List<Media>>(it.body.string()) }
        return media.sortedBy { it.id }
            .mapNotNull { item -> pages[item.post]?.let { it to item.source_url } }
            .distinctBy { it.first }
            .toMap()
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val all = getPopularManga(1).mangas
        return MangasPage(all.filter { it.title.contains(query.trim(), ignoreCase = true) }, false)
    }

    // Details

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("div.entry-title h2")?.text()?.trim().orEmpty()
            thumbnail_url = document.selectFirst("figure.wp-block-image img")?.absUrl("src")
                ?.replace("i0.wp.com/", "")?.substringBefore('?')
            val labels = document.select("p.wp-block-paragraph strong")
            // The label is spelled "Authir:" on some pages.
            author = labels.firstOrNull { it.text().trim().lowercase().startsWith("auth") }
                ?.let { it.text().substringAfter(':').trim().ifEmpty { it.nextSibling()?.toString()?.trim() } }
                ?.ifEmpty { null }
            description = labels.firstOrNull { it.text().trim().startsWith("Description") }?.parent()
                ?.nextElementSiblings()
                ?.takeWhile { !it.normalName().startsWith("h") }
                ?.map { it.wholeText().trim() }
                ?.filter { it.isNotEmpty() }
                ?.joinToString("\n\n")
        }
    }

    // Chapters

    override fun chapterListParse(response: Response): List<SChapter> =
        response.asJsoup().select(".lcp_catlist li a[href]").map { link ->
            SChapter.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                name = link.text().trim()
                chapter_number = CHAPTER_NUMBER.find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
            }
        }.reversed()

    // Text

    override fun chapterTextParse(response: Response): String {
        val content = response.asJsoup().selectFirst(".entry-content") ?: throw Exception("No chapter text found")
        content.select(JUNK).remove()
        return content.html()
    }

    @Serializable
    private class Page(val id: Int, val link: String)

    @Suppress("PropertyName")
    @Serializable
    private class Media(val id: Int, val post: Int? = null, val source_url: String)

    companion object {
        private val CHAPTER_NUMBER = Regex("""(?i)ch\.?\s*(\d+(?:\.\d+)?)""")

        /** The table of contents, navigation, sharing, subscription and donation widgets inside every chapter. */
        private const val JUNK = "script, style, form, ins, .category-post-dropdown-container, .nav-buttons, " +
            ".sharedaddy, .wp-block-jetpack-subscriptions__container, .psb-patreon-button, .code-block, " +
            ".likes-widget-placeholder, .wp-block-buttons, .adsbygoogle"
    }
}
