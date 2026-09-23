package app.yomikku.extension.en.novel7s

import app.yomikku.lib.lnfilters.LnFilters
import app.yomikku.lib.paced.Paced
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Novel7s. Ported from LNReader's novel7s plugin. A WordPress site where each novel is a category and each chapter a
 * post, so everything but the chapter text comes from the WordPress api.
 */
class Novel7s : HttpSource() {

    override val name = "Novel7s"
    override val baseUrl = "https://novel7s.com"
    override val lang = "en"
    override val supportsLatest = true

    private val rest = "$baseUrl/wp-json/wp/v2"
    private val json = Json { ignoreUnknownKeys = true }

    override fun getFilterList(): FilterList = LnFilters.fromResource(javaClass, "filters.json")

    // Listings

    private fun categoriesRequest(page: Int, sort: String, search: String? = null): Request {
        if (sort == "trending") {
            return GET("$baseUrl/wp-admin/admin-ajax.php?action=n7_load_more&type=trending&offset=${(page - 1) * PAGE_SIZE}", headers)
        }
        val url = "$rest/categories".toHttpUrl().newBuilder()
            .addQueryParameter("orderby", if (sort.startsWith("name")) "name" else sort)
            .addQueryParameter("order", if (sort == "name_asc") "asc" else "desc")
            .addQueryParameter("per_page", PAGE_SIZE.toString())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("hide_empty", "1")
            .addQueryParameter("_fields", "id,name,slug,count,description,link")
        if (search != null) url.addQueryParameter("search", search)
        return GET(url.build(), headers)
    }

    override fun popularMangaRequest(page: Int) = categoriesRequest(page, "count")

    override fun popularMangaParse(response: Response) = novelsParse(response)

    override fun latestUpdatesRequest(page: Int) = categoriesRequest(page, "id")

    override fun latestUpdatesParse(response: Response) = novelsParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val sort = LnFilters.queryParams(filters).toMap()["sort"] ?: "count"
        return if (query.isBlank()) categoriesRequest(page, sort) else categoriesRequest(page, "count", query.trim())
    }

    override fun searchMangaParse(response: Response) = novelsParse(response)

    private fun novelsParse(response: Response): MangasPage {
        val body = response.body.string()
        if (response.request.url.encodedPath.endsWith("admin-ajax.php")) {
            val items = json.decodeFromString<Trending>(body).items
            val novels = items.map { item ->
                SManga.create().apply {
                    setUrlWithoutDomain(item.url)
                    title = Jsoup.parse(item.name).text()
                    thumbnail_url = item.cover
                }
            }
            return MangasPage(novels, items.size == PAGE_SIZE)
        }
        val categories = json.decodeFromString<List<Category>>(body)
        val novels = categories.map { category ->
            SManga.create().apply {
                setUrlWithoutDomain(category.link)
                title = Jsoup.parse(category.name).text()
                thumbnail_url = cover(category.description)
            }
        }
        val pages = response.header("X-WP-TotalPages")?.toIntOrNull() ?: 1
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        return MangasPage(novels, page < pages)
    }

    /** The cover is the image in the category's description. */
    private fun cover(description: String?) = description?.let { Jsoup.parse(it).selectFirst("img")?.attr("src") }

    // Details

    private suspend fun category(manga: SManga): Category {
        val slug = manga.url.trim('/').substringAfterLast('/')
        val url = "$rest/categories?slug=$slug&_fields=id,name,slug,count,description,link"
        return client.newCall(GET(url, headers)).awaitSuccess()
            .use { json.decodeFromString<List<Category>>(it.body.string()) }
            .firstOrNull() ?: throw Exception("Novel not found")
    }

    override fun getMangaUrl(manga: SManga) = baseUrl + manga.url

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val category = category(manga)
        return SManga.create().apply {
            title = Jsoup.parse(category.name).text()
            thumbnail_url = cover(category.description)
            description = category.description?.let { Jsoup.parse(it).text().trim() }?.ifEmpty { null }
            initialized = true
        }
    }

    // Chapters: the posts in the novel's category, 100 per page.

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val categoryId = category(manga).id
        val posts = mutableListOf<Post>()
        var page = 1
        var pages: Int
        do {
            if (page > 1) delay(Paced.PAGE_DELAY_MS)
            val url = "$rest/posts?categories=$categoryId&orderby=date&order=asc&per_page=100&page=$page" +
                "&_fields=id,title,link,date"
            val response = Paced.fetch(client, GET(url, headers))
            pages = response.header("X-WP-TotalPages")?.toIntOrNull() ?: 1
            posts += response.use { json.decodeFromString<List<Post>>(it.body.string()) }
            page++
        } while (page <= pages)
        return posts.mapIndexed { index, post ->
            SChapter.create().apply {
                setUrlWithoutDomain(post.link)
                name = Jsoup.parse(post.title.rendered).text()
                chapter_number = index + 1f
                date_upload = runCatching { DATE_FORMAT.parse(post.date)?.time }.getOrNull() ?: 0L
            }
        }.reversed()
    }

    // Text

    override fun chapterTextParse(response: Response): String {
        val content = response.asJsoup().selectFirst(".entry-content") ?: throw Exception("No chapter text found")
        content.select("script, ins, .code-block").remove()
        return content.html()
    }

    @Serializable
    private class Category(val id: Int, val name: String, val link: String, val description: String? = null)

    @Serializable
    private class TrendingItem(val name: String, val url: String, val cover: String? = null)

    @Serializable
    private class Trending(val items: List<TrendingItem> = emptyList())

    @Serializable
    private class Rendered(val rendered: String)

    @Serializable
    private class Post(val title: Rendered, val link: String, val date: String)

    companion object {
        private const val PAGE_SIZE = 18
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)
    }
}
