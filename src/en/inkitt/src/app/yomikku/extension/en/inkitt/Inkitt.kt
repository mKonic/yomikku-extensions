package app.yomikku.extension.en.inkitt

import app.yomikku.lib.lnfilters.LnFilters
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
import okhttp3.Request
import okhttp3.Response

/**
 * Inkitt. Ported from LNReader's inkitt plugin. Trending stories and each genre are a single page on the site;
 * search pages through its api.
 */
class Inkitt : HttpSource() {

    override val name = "Inkitt"
    override val baseUrl = "https://www.inkitt.com"
    override val lang = "en"
    override val supportsLatest = false

    private val json = Json { ignoreUnknownKeys = true }

    override fun getFilterList(): FilterList = LnFilters.fromResource(javaClass, "filters.json")

    // Listings

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/api/1/homepage/trending_stories", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val stories = json.decodeFromString<Trending>(response.body.string()).response.items
        return MangasPage(stories.map(::toManga), false)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isBlank()) {
            val genre = LnFilters.queryParams(filters).toMap()["genres"]
            return if (genre.isNullOrEmpty()) popularMangaRequest(page) else GET("$baseUrl/genres/$genre", headers)
        }
        val url = "$baseUrl/api/2/search/title".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.trim())
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val path = response.request.url.encodedPath
        return when {
            path.startsWith("/genres/") -> {
                // A genre page renders its stories as cards carrying their data as attributes.
                val stories = response.asJsoup().select("div[data-story-id][data-title]").map { card ->
                    SManga.create().apply {
                        url = "/stories/${card.attr("data-story-id")}"
                        title = card.attr("data-title").trim()
                        thumbnail_url = card.attr("data-cover-url").ifEmpty { null }
                    }
                }.distinctBy { it.url }
                MangasPage(stories, false)
            }
            path.contains("trending") -> popularMangaParse(response)
            else -> {
                val stories = json.decodeFromString<SearchResult>(response.body.string()).stories
                MangasPage(stories.map(::toManga), stories.isNotEmpty())
            }
        }
    }

    private fun toManga(story: Story) = SManga.create().apply {
        url = "/stories/${story.id}"
        title = story.title.trim()
        thumbnail_url = story.vertical_cover?.url ?: story.vertical_cover?.iphone ?: story.cover?.url
    }

    // Details

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val fields = document.select("dl").associate { dl ->
            dl.selectFirst("dt")?.text()?.trim().orEmpty() to dl.selectFirst("dd")
        }
        return SManga.create().apply {
            title = document.selectFirst("h1.story-title")?.text()?.trim().orEmpty()
            author = fields["Author"]?.selectFirst("a.author-link")?.text()?.trim() ?: fields["Author"]?.text()?.trim()
            genre = fields["Genre"]?.select("a")?.joinToString { it.text().trim() }?.ifEmpty { fields["Genre"]?.text() }
            description = document.selectFirst("p.story-summary")?.wholeText()?.trim()
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
            status = when (fields["Status"]?.text()?.trim()) {
                "Complete" -> SManga.COMPLETED
                "Ongoing" -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
        }
    }

    // Chapters

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val id = manga.url.substringAfter("/stories/").substringBefore('/')
        val story = client.newCall(GET("$baseUrl/api/stories/$id", headers)).awaitSuccess()
            .use { json.decodeFromString<StoryDetails>(it.body.string()) }
        return story.chapters.map { chapter ->
            SChapter.create().apply {
                url = "/stories/$id/chapters/${chapter.chapter_number}"
                name = chapter.name
                chapter_number = chapter.chapter_number.toFloat()
            }
        }.reversed()
    }

    // Text

    override fun chapterTextParse(response: Response): String =
        response.asJsoup().selectFirst("div#chapterText")?.html() ?: throw Exception("No chapter text found")

    @Serializable
    private class Cover(val url: String? = null, val iphone: String? = null)

    @Suppress("PropertyName")
    @Serializable
    private class Story(val id: Long, val title: String, val cover: Cover? = null, val vertical_cover: Cover? = null)

    @Serializable
    private class StoryList(val items: List<Story> = emptyList())

    @Serializable
    private class Trending(val response: StoryList)

    @Serializable
    private class SearchResult(val stories: List<Story> = emptyList())

    @Suppress("PropertyName")
    @Serializable
    private class ChapterEntry(val chapter_number: Int, val name: String)

    @Serializable
    private class StoryDetails(val chapters: List<ChapterEntry> = emptyList())
}
