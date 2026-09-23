package app.yomikku.extension.en.dreamytranslations

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response

/**
 * Dreamy Translations. Ported from LNReader's dreamyTranslations plugin. The site is a Next.js app whose pages only
 * carry their data in the React Server Components stream, which is what a request with the `RSC` header returns.
 */
class DreamyTranslations : HttpSource() {

    override val name = "Dreamy Translations"
    override val baseUrl = "https://dreamy-translations.com"
    override val lang = "en"
    override val supportsLatest = false

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", baseUrl)
        .add("RSC", "1")

    // Listings: the series page lists every novel.

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/series", headers)

    override fun popularMangaParse(response: Response) = MangasPage(allNovels(response.body.bytes()), false)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET("$baseUrl/series#${query.trim()}", headers)

    override fun searchMangaParse(response: Response): MangasPage {
        val query = response.request.url.fragment.orEmpty().lowercase()
        return MangasPage(allNovels(response.body.bytes()).filter { query in it.title.lowercase() }, false)
    }

    private fun allNovels(stream: ByteArray): List<SManga> {
        val data = json.decodeFromJsonElement<SeriesList>(Rsc(stream).props("\"projects\""))
        return data.projects.map { project ->
            SManga.create().apply {
                url = "/novel/${project.slug}"
                title = project.title
                thumbnail_url = data.squareImageUrls[project.id.toString()]
            }
        }
    }

    // Details and chapters

    override fun mangaDetailsParse(response: Response): SManga {
        val data = json.decodeFromJsonElement<NovelPage>(Rsc(response.body.bytes()).props("\"chapters\":["))
        return SManga.create().apply {
            title = data.project.title
            thumbnail_url = data.coverUrl
            author = data.project.author
            genre = data.project.genres.joinToString().ifEmpty { null }
            description = data.project.synopsis ?: data.project.short_synopsis
            status = if (data.project.completed) SManga.COMPLETED else SManga.ONGOING
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val slug = response.request.url.encodedPath.removePrefix("/novel/")
        val data = json.decodeFromJsonElement<NovelPage>(Rsc(response.body.bytes()).props("\"chapters\":["))
        return data.chapters.map { chapter ->
            SChapter.create().apply {
                url = "/novel/$slug/chapter/${chapter.index}"
                name = (if (chapter.free) "" else "🔒 ") + chapter.title
                chapter_number = chapter.index
            }
        }.sortedByDescending { it.chapter_number }
    }

    // Text

    override fun chapterTextParse(response: Response): String {
        val rsc = Rsc(response.body.bytes())
        val data = json.decodeFromJsonElement<ChapterPage>(rsc.props("\"chapter\":{"))
        val chapter = data.chapter ?: throw Exception("Could not find the chapter")
        if (!chapter.free && data.hasAccess == false) throw Exception("This chapter requires premium access")
        // Long text is sent as a separate record the chapter refers to as "$<id>".
        val content = REF.matchEntire(chapter.content)?.let { rsc.text(it.groupValues[1]) } ?: chapter.content
        if (content.isBlank()) throw Exception("Dreamy Translations returned an empty chapter")
        return content.replace("\r\n", "\n").replace('\r', '\n')
            // Image sources come wrapped as markdown links: src="[url](url)".
            .replace(MARKDOWN_SRC, "$1$2$3")
            .split(PARAGRAPH_BREAK)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("") { paragraph ->
                if (STANDALONE_IMAGE.matches(paragraph)) paragraph else "<p>${paragraph.replace("\n", "<br>")}</p>"
            }
    }

    /** A React Server Components stream: numbered records, JSON or raw text. */
    private inner class Rsc(private val bytes: ByteArray) {
        private val text = bytes.toString(Charsets.UTF_8)

        /**
         * The props of the element whose record holds [marker]: records are `id:["$", type, key, props]`, so the
         * record is found by walking back from the marker to its opening bracket.
         */
        fun props(marker: String): JsonElement {
            val at = text.indexOf(marker).takeIf { it >= 0 } ?: throw Exception("Could not find the page's data")
            var start = text.lastIndexOf(":[", at)
            while (start >= 0) {
                val value = balanced(start + 1)?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
                if (value is JsonArray && value.size > 3 && value[3] is JsonObject) return value[3]
                start = text.lastIndexOf(":[", start - 1)
            }
            throw Exception("Could not read the page's data")
        }

        /** The JSON value opening at [start], up to its matching bracket. */
        private fun balanced(start: Int): String? {
            var depth = 0
            var inString = false
            var escaped = false
            for (i in start until text.length) {
                val c = text[i]
                when {
                    inString -> when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                    c == '"' -> inString = true
                    c == '[' || c == '{' -> depth++
                    c == ']' || c == '}' -> if (--depth == 0) return text.substring(start, i + 1)
                }
            }
            return null
        }

        /** The text record [id], whose length the stream gives in UTF-8 bytes. */
        fun text(id: String): String? {
            val header = Regex("""(?:^|\n)${Regex.escape(id)}:T([0-9a-fA-F]+),""").find(text) ?: return null
            val length = header.groupValues[1].toInt(16)
            // The header is plain ASCII, so its end in bytes is the byte length of everything before it.
            val offset = text.substring(0, header.range.last + 1).toByteArray(Charsets.UTF_8).size
            return String(bytes, offset, minOf(length, bytes.size - offset), Charsets.UTF_8)
        }
    }

    @Serializable
    private class Project(val id: Int, val title: String, val slug: String)

    @Serializable
    private class SeriesList(
        val projects: List<Project> = emptyList(),
        val squareImageUrls: Map<String, String> = emptyMap(),
    )

    @Suppress("PropertyName")
    @Serializable
    private class ProjectDetails(
        val title: String = "Untitled",
        val synopsis: String? = null,
        val short_synopsis: String? = null,
        val author: String? = null,
        val genres: List<String> = emptyList(),
        val completed: Boolean = false,
    )

    @Serializable
    private class ChapterEntry(val title: String, val index: Float, val free: Boolean = true)

    @Serializable
    private class NovelPage(
        val project: ProjectDetails,
        val chapters: List<ChapterEntry> = emptyList(),
        val coverUrl: String? = null,
    )

    @Serializable
    private class ChapterContent(val content: String = "", val free: Boolean = true)

    @Serializable
    private class ChapterPage(val chapter: ChapterContent? = null, val hasAccess: Boolean? = null)

    companion object {
        private val REF = Regex("""\$([0-9a-zA-Z]+)""")
        private val MARKDOWN_SRC = Regex("""(\bsrc\s*=\s*["'])\[([^\]]+)]\(\2\)(["'])""", RegexOption.IGNORE_CASE)
        private val PARAGRAPH_BREAK = Regex("""\n{2,}""")
        private val STANDALONE_IMAGE = Regex("""<img\b[^>]*/?>""", RegexOption.IGNORE_CASE)
    }
}
