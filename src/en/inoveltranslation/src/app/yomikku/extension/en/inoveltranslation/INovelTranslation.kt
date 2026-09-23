package app.yomikku.extension.en.inoveltranslation

import eu.kanade.tachiyomi.network.GET
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * iNovelTranslation. Ported from LNReader's inoveltranslation plugin. Novels and chapter lists come from the site's
 * Payload CMS api; chapter text from the chapter page, which renders it on the server.
 */
class INovelTranslation : HttpSource() {

    override val name = "iNovelTranslation"
    override val baseUrl = "https://inoveltranslation.com"
    override val lang = "en"
    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder().add("Referer", "$baseUrl/")

    // Listings

    private fun novelsUrl(page: Int) = "$baseUrl/api/novels".toHttpUrl().newBuilder()
        .addQueryParameter("limit", PAGE_SIZE.toString())
        .addQueryParameter("page", page.toString())

    override fun popularMangaRequest(page: Int) = GET(novelsUrl(page).build(), headers)

    override fun popularMangaParse(response: Response) = novelsParse(response)

    override fun latestUpdatesRequest(page: Int) = GET(novelsUrl(page).addQueryParameter("sort", "-updatedAt").build(), headers)

    override fun latestUpdatesParse(response: Response) = novelsParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET(novelsUrl(page).addQueryParameter("where[title][contains]", query.trim()).build(), headers)

    override fun searchMangaParse(response: Response) = novelsParse(response)

    private fun novelsParse(response: Response): MangasPage {
        val page = json.decodeFromString<Page<Novel>>(response.body.string())
        // Drafts the site has not titled yet come through with no title.
        return MangasPage(page.docs.filter { it.title != null }.map { it.toSManga() }, page.hasNextPage)
    }

    private fun Novel.toSManga() = SManga.create().apply {
        url = "/novels/$id"
        title = this@toSManga.title ?: "Untitled"
        thumbnail_url = cover?.url?.let { baseUrl + it }
    }

    // Details

    private fun idOf(manga: SManga) = manga.url.substringAfterLast('/')

    override fun mangaDetailsRequest(manga: SManga) = GET("$baseUrl/api/novels/${idOf(manga)}?depth=1", headers)

    override fun getMangaUrl(manga: SManga) = baseUrl + manga.url

    override fun mangaDetailsParse(response: Response): SManga {
        val novel = json.decodeFromString<Novel>(response.body.string())
        return novel.toSManga().apply {
            author = novel.author?.name
            genre = novel.tags.joinToString { it.name }.ifEmpty { null }
            description = (novel.sypnosis as? JsonObject)?.get("root")?.let(::lexicalText)?.trim()?.ifEmpty { null }
            status = if (novel.publication == "completed") SManga.COMPLETED else SManga.ONGOING
        }
    }

    /** Plain text of a Lexical rich text tree, a blank line after each paragraph. */
    private fun lexicalText(node: JsonElement): String {
        val obj = node as? JsonObject ?: return ""
        val children = (obj["children"] as? JsonArray).orEmpty()
        return when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "text" -> obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            "paragraph" -> children.joinToString("") { lexicalText(it) } + "\n\n"
            "listitem" -> "• " + children.joinToString("") { lexicalText(it) } + "\n"
            "linebreak" -> "\n"
            else -> children.joinToString("") { lexicalText(it) }
        }
    }

    // Chapters

    override fun chapterListRequest(manga: SManga) =
        GET("$baseUrl/api/chapters?where[novel][equals]=${idOf(manga)}&limit=$ALL&depth=0&sort=-chapter", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val chapters = json.decodeFromString<Page<Chapter>>(response.body.string()).docs
        return chapters.map { chapter ->
            SChapter.create().apply {
                url = "/chapters/${chapter.id}"
                val number = if (chapter.chapter % 1f == 0f) chapter.chapter.toInt().toString() else chapter.chapter.toString()
                name = "Ch. $number" + (if (chapter.tier != null) " 🔒" else "") + (chapter.title?.let { " - $it" } ?: "")
                chapter_number = chapter.chapter
                date_upload = chapter.updatedAt?.let { runCatching { DATE_FORMAT.parse(it.take(19))?.time }.getOrNull() } ?: 0L
            }
        }.sortedByDescending { it.chapter_number }
    }

    // Text

    override fun chapterTextParse(response: Response): String {
        val content = response.asJsoup().selectFirst("section[data-sentry-component=RichText]")
            ?: throw Exception("This chapter is locked or has no text")
        content.select("script, style").remove()
        content.select("[style]").removeAttr("style")
        return content.html()
    }

    @Serializable
    private class Page<T>(val docs: List<T> = emptyList(), val hasNextPage: Boolean = false)

    @Serializable
    private class Media(val url: String? = null)

    @Serializable
    private class Named(val name: String = "")

    @Serializable
    private class Novel(
        val id: String,
        val title: String? = null,
        val cover: Media? = null,
        val author: Named? = null,
        val publication: String? = null,
        val tags: List<Named> = emptyList(),
        val sypnosis: JsonElement? = null,
    )

    @Serializable
    private class Chapter(
        val id: String,
        val title: String? = null,
        val chapter: Float,
        val tier: JsonElement? = null,
        val updatedAt: String? = null,
    )

    companion object {
        private const val PAGE_SIZE = 50
        private const val ALL = 10000
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
