package app.yomikku.extension.en.wct

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response
import java.util.Calendar
import java.util.TimeZone

/**
 * Witch Cult Translations, the fan translation of the Re:Zero web novel from arc 5 on. Ported from LNReader's wct
 * plugin. The site has the one novel.
 */
class WitchCultTranslations : HttpSource() {

    override val name = "Witch Cult Translations"
    override val baseUrl = "https://witchculttranslation.com"
    override val lang = "en"
    override val supportsLatest = false

    private suspend fun novel(): SManga {
        val home = client.newCall(GET(baseUrl, headers)).awaitSuccess().asJsoup()
        return SManga.create().apply {
            url = TOC
            title = TITLE
            // The newest arc's cover heads the home page's list of arcs.
            thumbnail_url = home.select(".entry-content h1 img").lastOrNull()?.absUrl("src")
            author = AUTHOR
            description = DESCRIPTION
            status = SManga.ONGOING
            initialized = true
        }
    }

    override suspend fun getPopularManga(page: Int) = MangasPage(listOf(novel()), false)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val words = query.lowercase().filter { it.isLetterOrDigit() }
        val matches = words.isEmpty() || words in TITLE.lowercase().filter { it.isLetterOrDigit() } ||
            words in "rezero"
        return MangasPage(if (matches) listOf(novel()) else emptyList(), false)
    }

    override suspend fun getMangaDetails(manga: SManga) = novel()

    // Chapters: the table of contents lists every arc; the translation starts at arc 5.

    override fun chapterListParse(response: Response): List<SChapter> {
        val content = response.asJsoup().selectFirst(".entry-content") ?: return emptyList()
        val chapters = mutableListOf<SChapter>()
        var arc = 0
        for (element in content.children()) {
            when (element.normalName()) {
                "h1", "h2" -> {
                    val text = element.text().trim()
                    if (SIDE_CONTENT.containsMatchIn(text)) break
                    ARC.find(text)?.let { arc = it.groupValues[1].toInt() }
                }
                "ul" -> if (arc >= FIRST_ARC) {
                    element.select("li > a[href]").forEach { link ->
                        val href = link.absUrl("href")
                        if (!href.contains("witchculttranslation.com/") || link.text().isBlank()) return@forEach
                        chapters += SChapter.create().apply {
                            setUrlWithoutDomain(href)
                            name = "Arc $arc, ${link.text().trim()}"
                            chapter_number = chapters.size + 1f
                            date_upload = DATE.find(url)?.destructured?.let { (y, m, d) ->
                                Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                                    clear()
                                    set(y.toInt(), m.toInt() - 1, d.toInt())
                                }.timeInMillis
                            } ?: 0L
                        }
                    }
                }
            }
        }
        return chapters.reversed()
    }

    // Text

    override fun chapterTextParse(response: Response): String {
        val document = response.asJsoup()
        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val content = document.selectFirst(".entry-content") ?: throw Exception("No chapter text found")
        content.select("script, #patreon-snippet, .sharedaddy, .jp-relatedposts, #jp-post-flair").remove()
        return "<h1>$title</h1>" + content.html()
    }

    companion object {
        private const val TOC = "/table-of-content/"
        private const val TITLE = "Re:Zero kara Hajimeru Isekai Seikatsu"
        private const val AUTHOR = "Tappei Nagatsuki"
        private const val FIRST_ARC = 5
        private val ARC = Regex("""(?i)^Arc\s+(\d+)""")
        private val SIDE_CONTENT = Regex("""(?i)^Side Content""")
        private val DATE = Regex("""^/(\d{4})/(\d{2})/(\d{2})/""")
        private const val DESCRIPTION = "Fan translation of the Re:Zero web novel (Arc 5 onwards).\n\n" +
            "Suddenly, Natsuki Subaru, a shut-in student, is summoned to another world on his way home from the " +
            "convenience store. A completely ordinary person with no knowledge, skills, combat abilities, or " +
            "communication skills, he's thrown into this other world without any cheat bonuses and must desperately " +
            "try to survive. The only blessing he receives is the painful ability to \"return by death,\" which " +
            "allows him to rewind time after dying! In this other world where he has no one to rely on, how many " +
            "times will he die, and what will he ultimately gain?"
    }
}
