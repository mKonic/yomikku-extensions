package app.yomikku.extension.en.mvlempyr

import app.yomikku.lib.lnfilters.LnFilters
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import okhttp3.Response
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

/**
 * MVLEMPYR. Ported from LNReader's mvlempyr plugin. The site has no listing pages: its WordPress backend
 * (chap.heliosarchive.online) serves the whole catalogue, about 12,000 novels in two gzipped requests of 10,000, so listings,
 * search and filters all work on one copy of it, loaded once. Chapter lists are WordPress posts tagged with a number
 * derived from the novel's code; chapter text is on the site's own pages.
 */
class Mvlempyr : HttpSource() {

    override val name = "MVLEMPYR"
    override val baseUrl = "https://www.mvlempyr.io"
    override val lang = "en"
    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }

    override fun getFilterList(): FilterList = LnFilters.fromResource(javaClass, "filters.json")

    // Catalogue

    private class Novel(
        val name: String,
        val slug: String,
        val code: String,
        val aliases: String,
        val genres: Set<String>,
        val tags: Set<String>,
        val rating: Double,
        val reviews: Int,
        val chapters: Int,
        val created: Long,
        val random: Int,
    )

    private val catalogueLock = Mutex()
    private var catalogue: List<Novel>? = null

    private suspend fun catalogue(): List<Novel> = catalogueLock.withLock {
        catalogue ?: run {
            val first = client.newCall(GET(cataloguePage(1), headers)).awaitSuccess()
            val pages = first.header("X-WP-TotalPages")?.toIntOrNull() ?: 1
            coroutineScope {
                val rest = (2..pages).map { page ->
                    async { client.newCall(GET(cataloguePage(page), headers)).awaitSuccess().body.string() }
                }
                listOf(first.body.string()) + rest.awaitAll()
            }.flatMap(::novels)
        }.also { catalogue = it }
    }

    private fun cataloguePage(page: Int) = "$API/mvl-novels?per_page=$CATALOGUE_PAGE_SIZE&page=$page"

    private fun novels(body: String): List<Novel> {
        val random = Random(body.length)
        return json.parseToJsonElement(body).jsonArray.mapNotNull { element ->
            val fields = element as? JsonObject ?: return@mapNotNull null
            val slug = fields.text("slug") ?: return@mapNotNull null
            Novel(
                name = fields.text("name") ?: slug,
                slug = slug,
                code = fields.text("novel-code") ?: return@mapNotNull null,
                aliases = fields.text("associated-names").orEmpty(),
                genres = fields.list("genre").map(::slugify).toSet(),
                tags = fields.list("tags").map(::slugify).toSet(),
                rating = fields.text("average-review")?.toDoubleOrNull() ?: 0.0,
                reviews = fields.text("total-reviews")?.toIntOrNull() ?: 0,
                chapters = fields.text("total-chapters")?.toIntOrNull() ?: 0,
                created = fields.text("createdOn")?.let(::parseDate) ?: 0L,
                random = random.nextInt(),
            )
        }
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.ifEmpty { null }

    private fun JsonObject.list(key: String) =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

    // Filter values are the site's tag slugs; novels carry display names.
    private fun slugify(name: String) = name.lowercase().replace(NON_SLUG, "-").trim('-')

    // Listings: the catalogue is fetched here, so the requests below are never sent.

    override suspend fun getPopularManga(page: Int) = listing(page, getFilterList())

    override suspend fun getLatestUpdates(page: Int) = listing(page, FilterList(), sort = "created")

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) = listing(page, filters, query)

    private suspend fun listing(page: Int, filters: FilterList, query: String = "", sort: String? = null): MangasPage {
        val params = LnFilters.queryParams(filters).toMap()
        val groups = filters.filterIsInstance<LnFilters.ExcludableGroup>().associateBy { it.key }
        val genres = groups["genre"]
        val tags = groups["tag"]
        val term = query.trim().lowercase()
        val matches = catalogue().filter { novel ->
            (term.isEmpty() || term in novel.name.lowercase() || term in novel.aliases.lowercase()) &&
                (genres == null || (novel.genres.containsAll(genres.included) && genres.excluded.none { it in novel.genres })) &&
                (tags == null || (novel.tags.containsAll(tags.included) && tags.excluded.none { it in novel.tags }))
        }
        val sorted = when (sort ?: params["order"] ?: "reviewCount") {
            "created" -> matches.sortedByDescending { it.created }
            "avgReview" -> matches.sortedWith(compareByDescending<Novel> { it.rating }.thenByDescending { it.reviews })
            "chapterCount" -> matches.sortedByDescending { it.chapters }
            "random" -> matches.sortedBy { it.random }
            else -> matches.sortedByDescending { it.reviews }
        }
        val novels = sorted.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE).map { novel ->
            SManga.create().apply {
                url = "/novel/${novel.slug}"
                title = novel.name
                thumbnail_url = "$IMAGES/600/${novel.code}.webp"
            }
        }
        return MangasPage(novels, page * PAGE_SIZE < sorted.size)
    }

    override fun popularMangaRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun popularMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        throw UnsupportedOperationException()

    override fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()

    // Details

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val info = document.select("div.additionalinfo.tm10 > div.textwrapper")
        return SManga.create().apply {
            title = document.selectFirst("h1.novel-title")?.text()?.trim().orEmpty()
            thumbnail_url = document.selectFirst("img.novel-image")?.absUrl("src")
            description = document.selectFirst("div.synopsis.w-richtext")?.select("p")
                ?.map { it.text().trim() }?.filter { it.isNotEmpty() }?.joinToString("\n\n")
            author = info.firstOrNull { it.text().startsWith("Author:") }?.text()?.removePrefix("Author:")?.trim()
            genre = document.select(".genre-tags").joinToString { it.text().trim() }
            status = when (document.selectFirst(".novelstatustextlarge")?.text()?.trim()?.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                "dropped" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
        }
    }

    // Chapters

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val document = client.newCall(GET(baseUrl + manga.url, headers)).awaitSuccess().asJsoup()
        val code = document.selectFirst("#novel-code")?.text()?.trim()?.toBigIntegerOrNull()
            ?: throw Exception("Could not find the novel code")
        // The posts are tagged with 7^code mod 1999999997.
        val tag = SEVEN.modPow(code, TAG_MODULUS)
        val first = client.newCall(GET(postsUrl(tag, 1), headers)).awaitSuccess()
        val pages = first.header("X-WP-TotalPages")?.toIntOrNull() ?: 1
        val posts = coroutineScope {
            val rest = (2..pages).map { page ->
                async { client.newCall(GET(postsUrl(tag, page), headers)).awaitSuccess().body.string() }
            }
            listOf(first.body.string()) + rest.awaitAll()
        }.flatMap { json.parseToJsonElement(it).jsonArray }
        return posts.mapNotNull(::chapter).distinctBy { it.url }.sortedByDescending { it.chapter_number }
    }

    private fun postsUrl(tag: BigInteger, page: Int) = "$API/posts?tags=$tag&per_page=$POSTS_PAGE_SIZE&page=$page"

    private fun chapter(post: JsonElement): SChapter? {
        val fields = post as? JsonObject ?: return null
        val acf = fields["acf"]?.jsonObject ?: return null
        val code = acf.text("novel_code") ?: return null
        val number = acf.text("chapter_number") ?: return null
        return SChapter.create().apply {
            url = "/chapter/$code-$number"
            name = acf.text("ch_name") ?: "Chapter $number"
            chapter_number = number.toFloatOrNull() ?: -1f
            date_upload = fields.text("date")?.let(::parseDate) ?: 0L
        }
    }

    // Text

    override fun chapterTextParse(response: Response): String {
        val document = response.asJsoup()
        if (document.title().trim() in CHALLENGE_TITLES) throw Exception("Captcha error, please open in WebView")
        val content = document.selectFirst("#chapter > span") ?: throw Exception("No chapter text found")
        content.select("script, style").remove()
        return content.html()
    }

    private fun parseDate(text: String): Long = DATE_FORMATS.firstNotNullOfOrNull { format ->
        runCatching { format.parse(text)?.time }.getOrNull()
    } ?: 0L

    companion object {
        private const val API = "https://chap.heliosarchive.online/wp-json/wp/v2"
        private const val IMAGES = "https://assets.mvlempyr.app/images"
        private const val CATALOGUE_PAGE_SIZE = 10000
        private const val POSTS_PAGE_SIZE = 500
        private const val PAGE_SIZE = 30
        private val SEVEN = BigInteger.valueOf(7)
        private val TAG_MODULUS = BigInteger.valueOf(1999999997)
        private val NON_SLUG = Regex("[^a-z0-9]+")
        private val CHALLENGE_TITLES = setOf("Attention Required! | Cloudflare", "Just a moment...")
        private val DATE_FORMATS = listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss").map {
            SimpleDateFormat(it, Locale.ENGLISH).apply { timeZone = TimeZone.getTimeZone("UTC") }
        }
    }
}
