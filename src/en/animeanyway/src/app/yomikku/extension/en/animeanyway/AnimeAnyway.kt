package app.yomikku.extension.en.animeanyway

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Entities

/**
 * Anime Anyway. Ported from LNReader's animeAnyway plugin. The site publishes each volume as its own book, so each
 * volume is a novel here too; everything comes from the data its Next.js pages embed.
 */
class AnimeAnyway : HttpSource() {

    override val name = "Anime Anyway"
    override val baseUrl = "https://animeanyway.com"
    override val lang = "en"
    override val supportsLatest = false

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private inline fun <reified T> pageProps(response: Response): T {
        val data = NEXT_DATA.find(response.body.string())?.groupValues?.get(1) ?: throw Exception("Could not find the page's data")
        return json.decodeFromString<NextData<T>>(data).props.pageProps
    }

    /** The site's own "Year 3 Vol. 4" titles don't name the series; its other titles do. */
    private fun displayName(title: String) = if (YEAR_VOLUME.containsMatchIn(title)) "Classroom of the Elite: $title" else title

    // Listings: the home page lists every volume.

    override fun popularMangaRequest(page: Int) = GET(baseUrl, headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val volumes = pageProps<Home>(response).allVolumes
        return MangasPage(volumes.map { it.toSManga() }, false)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/#${query.trim()}", headers)

    override fun searchMangaParse(response: Response): MangasPage {
        val query = response.request.url.fragment.orEmpty().normalize()
        val words = query.split(' ').filter { it.isNotEmpty() }
        val matches = pageProps<Home>(response).allVolumes
            .map { it.toSManga() }
            .filter { manga -> manga.title.normalize().let { title -> words.all { it in title } } }
        return MangasPage(matches, false)
    }

    private fun String.normalize() = lowercase().replace(NON_WORD, " ").trim()

    private fun Volume.toSManga() = SManga.create().apply {
        url = "/$volkeyword"
        title = displayName(this@toSManga.title)
        thumbnail_url = mainImage?.asset?.url
    }

    // Details and chapters

    override fun mangaDetailsParse(response: Response): SManga {
        val volume = pageProps<VolumePage>(response).vol ?: throw Exception("This novel is not available on Anime Anyway")
        return volume.toSManga().apply {
            description = volume.synopsis.filter { it._type == "block" }
                .map { block -> block.children.joinToString("") { it.text } }
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
                .ifEmpty { null }
            status = if ((volume.progress ?: 0f) >= 100f) SManga.COMPLETED else SManga.ONGOING
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val volume = pageProps<VolumePage>(response).vol ?: return emptyList()
        return volume.chapters.mapIndexed { index, chapter ->
            SChapter.create().apply {
                url = "/${volume.volkeyword}/${chapter.chkeyword}"
                name = chapter.title
                chapter_number = index + 1f
            }
        }.reversed()
    }

    // Text: Sanity portable text, rendered to HTML.

    override fun chapterTextParse(response: Response): String {
        val blocks = pageProps<ChapterPage>(response).chapter?.content ?: throw Exception("This chapter is not available")
        return blocks.mapNotNull { block ->
            when (block._type) {
                "horizontalLine" -> "<hr>"
                "image" -> block.asset?._ref?.let(::imageUrl)?.let { "<img src=\"$it\">" }
                "block" -> {
                    val inner = block.children.joinToString("") { span ->
                        var text = Entities.escape(span.text)
                        if ("strong" in span.marks) text = "<strong>$text</strong>"
                        if ("em" in span.marks) text = "<em>$text</em>"
                        text
                    }
                    val tag = if (block.style == "h2") "h2" else "p"
                    inner.takeIf { it.isNotBlank() }?.let { "<$tag>$it</$tag>" }
                }
                else -> null
            }
        }.joinToString("\n").ifEmpty { throw Exception("This chapter appears to be empty") }
    }

    /** Portable text image blocks carry only an asset reference; the CDN url is built from it. */
    private fun imageUrl(ref: String): String? {
        val match = IMAGE_REF.matchEntire(ref) ?: return null
        val (id, size, format) = match.destructured
        return "$SANITY_IMAGES$id-$size.$format"
    }

    @Serializable
    private class NextData<T>(val props: Props<T>)

    @Serializable
    private class Props<T>(val pageProps: T)

    @Serializable
    private class Home(val allVolumes: List<Volume> = emptyList())

    @Serializable
    private class VolumePage(val vol: Volume? = null)

    @Serializable
    private class ChapterPage(val chapter: ChapterContent? = null)

    @Serializable
    private class ChapterContent(val content: List<Block> = emptyList())

    @Serializable
    private class Asset(val url: String? = null)

    @Serializable
    private class Image(val asset: Asset? = null)

    @Serializable
    private class ChapterEntry(val chkeyword: String, val title: String)

    @Serializable
    private class Volume(
        val title: String,
        val volkeyword: String,
        val mainImage: Image? = null,
        val progress: Float? = null,
        val synopsis: List<Block> = emptyList(),
        val chapters: List<ChapterEntry> = emptyList(),
    )

    @Serializable
    private class Span(val text: String = "", val marks: List<String> = emptyList())

    @Suppress("PropertyName")
    @Serializable
    private class AssetRef(val _ref: String? = null)

    @Suppress("PropertyName")
    @Serializable
    private class Block(
        val _type: String = "",
        val style: String? = null,
        val children: List<Span> = emptyList(),
        val asset: AssetRef? = null,
    )

    companion object {
        private const val SANITY_IMAGES = "https://cdn.sanity.io/images/m1xj6lbt/production/"
        private val NEXT_DATA = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        private val YEAR_VOLUME = Regex("""^Year\s+\d+\s+Vol(?:ume|\.)?\s*\d+""", RegexOption.IGNORE_CASE)
        private val IMAGE_REF = Regex("""image-([a-f0-9]+)-(\d+x\d+)-(\w+)""")
        private val NON_WORD = Regex("""[^a-z0-9]+""")
    }
}
