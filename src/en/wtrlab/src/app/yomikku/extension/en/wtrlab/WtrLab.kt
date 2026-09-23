package app.yomikku.extension.en.wtrlab

import app.yomikku.lib.lnfilters.LnFilters
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * WTR-LAB, machine translations of Chinese web novels. Ported from LNReader's wtrlab plugin.
 *
 * Chapters are read in the site's AI translation, which it serves to anyone. When a chapter has none, its web
 * translation comes encrypted with a key from the site's scripts, and is decrypted and machine-translated on the
 * device as the site's own reader does.
 */
class WtrLab : HttpSource() {

    override val name = "WTR-LAB"
    override val baseUrl = "https://wtr-lab.com"
    override val lang = "en"
    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder().add("Referer", "$baseUrl/")

    override fun getFilterList(): FilterList = LnFilters.fromResource(javaClass, "filters.json")

    // Listings: the novel finder's page data, which needs the site's current build id.

    private var buildId: String? = null

    private suspend fun buildId(): String = buildId ?: client.newCall(GET("$baseUrl/en/novel-finder", headers)).awaitSuccess()
        .use { NEXT_DATA.find(it.body.string())?.groupValues?.get(1) }
        ?.let { json.decodeFromString<BuildInfo>(it).buildId }
        ?.also { buildId = it }
        ?: throw Exception("Could not find the site's build id")

    override suspend fun getPopularManga(page: Int) = getSearchManga(page, "", getFilterList())

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val params = LnFilters.queryParams(filters).toMap()
        val url = "$baseUrl/_next/data/${buildId()}/en/novel-finder.json".toHttpUrl().newBuilder().apply {
            listOf("orderBy", "order", "status", "release_status", "addition_age").forEach { key ->
                params[key]?.let { addQueryParameter(key, it) }
            }
            addQueryParameter("page", page.toString())
            if (query.isNotBlank()) addQueryParameter("text", query.trim())
            params["min_chapters"]?.let { addQueryParameter("count_value", it) }
            params["min_rating"]?.let { addQueryParameter("minr", it) }
            params["min_review_count"]?.let { addQueryParameter("minrc", it) }
            filters.filterIsInstance<LnFilters.ExcludableGroup>().forEach { group ->
                val (include, operator, exclude) = if (group.key == "genres") Triple("gi", "gc", "ge") else Triple("ti", "tc", "te")
                if (group.included.isNotEmpty()) {
                    addQueryParameter(include, group.included.joinToString(","))
                    params[if (group.key == "genres") "genre_operator" else "tag_operator"]?.let { addQueryParameter(operator, it) }
                }
                if (group.excluded.isNotEmpty()) addQueryParameter(exclude, group.excluded.joinToString(","))
            }
        }.build()
        val response = client.newCall(GET(url, headers)).await()
        if (!response.isSuccessful) {
            response.close()
            // A new deploy retires the old build id, answered with 404; the next try reads the current one.
            buildId = null
            throw Exception("HTTP error ${response.code}")
        }
        val series = response.use { json.decodeFromString<FinderData>(it.body.string()).pageProps.series }
        val novels = series.distinctBy { it.raw_id }.map { it.toSManga() }
        return MangasPage(novels, novels.size >= FINDER_PAGE)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val body = buildJsonObject { put("page", page) }.toString().toRequestBody(JSON_TYPE)
        return POST("$baseUrl/api/home/recent", headers, body)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val recent = json.decodeFromString<Recent>(response.body.string()).data
        return MangasPage(recent.map { it.serie.toSManga() }, recent.isNotEmpty())
    }

    private fun Serie.toSManga() = SManga.create().apply {
        url = "/en/novel/$raw_id/$slug"
        title = data.title ?: slug
        thumbnail_url = data.image
    }

    // Details

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val props = pageProps(document)
        val serie = props.serie.serie_data
        return serie.toSManga().apply {
            author = serie.data.author?.ifBlank { null }
            description = serie.data.description
            status = when (serie.status) {
                0 -> SManga.ONGOING
                1 -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            // Genres are ids here; the page's genre links name them.
            val genreNames = document.select("a[href*=novel-list?genre=]").mapNotNull { link ->
                GENRE_ID.find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull()?.let { it to link.text().trim() }
            }.toMap()
            genre = (serie.genres.mapNotNull { genreNames[it]?.replaceFirstChar(Char::uppercase) } + props.tags.map { it.title })
                .filter { it.isNotBlank() }.distinct().joinToString().ifEmpty { null }
        }
    }

    private fun pageProps(document: Document): PageProps {
        val data = document.selectFirst("#__NEXT_DATA__")?.data() ?: throw Exception("Could not find the page's data")
        return json.decodeFromString<NextData>(data).props.pageProps
    }

    // Chapters: 500 at a time.

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val (rawId, slug) = PATH.find(manga.url)?.destructured ?: throw Exception("Invalid novel path")
        val chapters = mutableListOf<ChapterEntry>()
        var start = 1
        while (true) {
            val url = "$baseUrl/api/chapters/$rawId?start=$start&end=${start + CHAPTER_BATCH - 1}"
            val batch = client.newCall(GET(url, headers)).awaitSuccess()
                .use { json.decodeFromString<ChapterList>(it.body.string()).chapters }
            chapters += batch
            if (batch.size < CHAPTER_BATCH) break
            start += CHAPTER_BATCH
        }
        return chapters.map { chapter ->
            SChapter.create().apply {
                url = "/en/novel/$rawId/$slug/chapter-${chapter.order}"
                name = chapter.title ?: chapter.name ?: "Chapter ${chapter.order}"
                chapter_number = chapter.order.toFloat()
                date_upload = chapter.updated_at?.let { runCatching { DATE_FORMAT.parse(it.take(19))?.time }.getOrNull() } ?: 0L
            }
        }.sortedByDescending { it.chapter_number }
    }

    // Text

    override suspend fun getChapterText(chapter: SChapter): String {
        val (rawId, _, number) = CHAPTER_PATH.find(chapter.url)?.destructured ?: throw Exception("Invalid chapter path")
        val failures = mutableListOf<String>()
        for (mode in MODES) {
            val body = buildJsonObject {
                put("translate", mode)
                put("language", "en")
                put("raw_id", rawId.toLong())
                put("chapter_no", number.toLong())
                put("retry", false)
                put("force_retry", false)
            }.toString().toRequestBody(JSON_TYPE)
            val readerHeaders = headers.newBuilder().set("Referer", baseUrl + chapter.url).add("Accept", "application/json").build()
            val result = client.newCall(POST("$baseUrl/api/reader/get", readerHeaders, body)).await()
                .use { response -> runCatching { json.decodeFromString<Reader>(response.body.string()) }.getOrNull() }
            val content = result?.data?.data
            if (result == null || result.success == false || content?.body == null) {
                failures += "$mode: ${result?.error ?: result?.message ?: "unavailable"}"
                continue
            }
            val lines = when (val raw = content.body) {
                is JsonArray -> raw.map { it.jsonPrimitive.contentOrNull.orEmpty() }
                is JsonPrimitive -> machineTranslate(decrypt(raw.content, encryptionKey(chapter.url)))
                else -> continue
            }
            val terms = content.glossary_data?.terms?.map { it.firstOrNull().orEmpty() }.orEmpty()
            val title = result.chapter?.title?.let { "<h3>${Entities.escape(it)}</h3>" }.orEmpty()
            return title + lines.joinToString("") { line ->
                val text = if (terms.isEmpty()) line else GLOSSARY.replace(line) { terms.getOrNull(it.groupValues[1].toInt()) ?: it.value }
                "<p>${Entities.escape(text)}</p>"
            }
        }
        throw Exception("None of the translations could be loaded (${failures.joinToString("; ")})")
    }

    private var encryptionKey: String? = null

    /** The key the site's reader decrypts with, written into one of its scripts. */
    private suspend fun encryptionKey(chapterUrl: String): String {
        encryptionKey?.let { return it }
        val page = client.newCall(GET(baseUrl + chapterUrl, headers)).awaitSuccess().use { it.asJsoup() }
        val scripts = page.select("script[src]").map { it.attr("src") }.filter { it.startsWith("/_next/") }.distinct()
        for (src in scripts) {
            val code = client.newCall(GET(baseUrl + src, headers)).awaitSuccess().use { it.body.string() }
            val at = code.indexOf(KEY_MARKER)
            if (at >= 0) return code.substring(at + KEY_MARKER.length, at + KEY_MARKER.length + 32).also { encryptionKey = it }
        }
        throw Exception("Could not find the chapter key")
    }

    /** AES-GCM, sent as "arr:" or "str:" then iv:tag:ciphertext in base64; "arr:" holds a JSON list of lines. */
    private fun decrypt(encrypted: String, key: String): List<String> {
        val isList = encrypted.startsWith("arr:")
        val parts = encrypted.removePrefix("arr:").removePrefix("str:").split(':')
        if (parts.size != 3) throw Exception("Unexpected chapter format")
        val (iv, tag, cipherText) = parts.map { Base64.getDecoder().decode(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.take(32).toByteArray(), "AES"), GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(cipherText + tag).toString(Charsets.UTF_8)
        return if (isList) json.decodeFromString<List<String>>(plain) else plain.lines()
    }

    /** The raw Chinese, through the same Google translation the site's reader uses. */
    private suspend fun machineTranslate(lines: List<String>): List<String> {
        val wrapped = buildJsonArray { lines.forEachIndexed { i, line -> add(JsonPrimitive("<a i=$i>${Entities.escape(line)}</a>")) } }
        val body = "[[$wrapped,\"zh-CN\",\"en\"],\"te_lib\"]".toRequestBody("application/json+protobuf".toMediaType())
        val translateHeaders = Headers.Builder()
            .add("X-Goog-API-Key", TRANSLATE_KEY)
            .add("Referer", "$baseUrl/")
            .build()
        val translated = client.newCall(POST(TRANSLATE_URL, translateHeaders, body)).awaitSuccess()
            .use { json.parseToJsonElement(it.body.string()) }
        val out = ((translated as? JsonArray)?.firstOrNull() as? JsonArray)?.map { it.jsonPrimitive.content }
            ?: throw Exception("The translation came back empty")
        return out.map { org.jsoup.Jsoup.parseBodyFragment(it).text() }
    }

    override fun getChapterUrl(chapter: SChapter) = baseUrl + chapter.url

    @Serializable
    private class BuildInfo(val buildId: String)

    @Serializable
    private class SerieData(
        val title: String? = null,
        val image: String? = null,
        val author: String? = null,
        val description: String? = null,
    )

    @Suppress("PropertyName")
    @Serializable
    private class Serie(
        val raw_id: Long,
        val slug: String,
        val status: Int? = null,
        val data: SerieData = SerieData(),
        val genres: List<Int> = emptyList(),
    )

    @Serializable
    private class FinderProps(val series: List<Serie> = emptyList())

    @Serializable
    private class FinderData(val pageProps: FinderProps)

    @Serializable
    private class RecentEntry(val serie: Serie)

    @Serializable
    private class Recent(val data: List<RecentEntry> = emptyList())

    @Suppress("PropertyName")
    @Serializable
    private class SerieWrapper(val serie_data: Serie)

    @Serializable
    private class Tag(val title: String = "")

    @Serializable
    private class PageProps(val serie: SerieWrapper, val tags: List<Tag> = emptyList())

    @Serializable
    private class Props(val pageProps: PageProps)

    @Serializable
    private class NextData(val props: Props)

    @Suppress("PropertyName")
    @Serializable
    private class ChapterEntry(val order: Int, val title: String? = null, val name: String? = null, val updated_at: String? = null)

    @Serializable
    private class ChapterList(val chapters: List<ChapterEntry> = emptyList())

    @Serializable
    private class Glossary(val terms: List<List<String>> = emptyList())

    @Suppress("PropertyName")
    @Serializable
    private class ReaderContent(val body: JsonElement? = null, val glossary_data: Glossary? = null)

    @Serializable
    private class ReaderData(val data: ReaderContent? = null)

    @Serializable
    private class ReaderChapter(val title: String? = null)

    @Serializable
    private class Reader(
        val success: Boolean? = null,
        val error: String? = null,
        val message: String? = null,
        val chapter: ReaderChapter? = null,
        val data: ReaderData? = null,
    )

    companion object {
        private const val FINDER_PAGE = 10
        private const val CHAPTER_BATCH = 500
        private val MODES = listOf("ai", "web")
        private const val KEY_MARKER = "TextEncoder().encode(\""
        private const val TRANSLATE_URL = "https://translate-pa.googleapis.com/v1/translateHtml"

        // The public key Google's own website translation widget uses, as the site's reader does.
        private const val TRANSLATE_KEY = "AIzaSyATBXajvzQLTDHEQbcpq0Ihe0vWDHmO520"
        private val JSON_TYPE = "application/json".toMediaType()
        private val NEXT_DATA = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        private val PATH = Regex("""/(?:serie-|novel/)(\d+)/([^/]+)""")
        private val CHAPTER_PATH = Regex("""/(?:serie-|novel/)(\d+)/([^/]+)/chapter-(\d+)""")
        private val GENRE_ID = Regex("""genre=(\d+)""")
        private val GLOSSARY = Regex("""(?:wtr-lab\s+)?※([0-9]+)[⛬〓]""")
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
