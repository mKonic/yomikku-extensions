package app.yomikku.multisrc.ifreedom

import app.yomikku.lib.wpcommon.WpCommon.checkBlocked
import app.yomikku.lib.wpcommon.WpCommon.imageUrl
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.util.Calendar

/**
 * Russian translation sites on the ifreedom WordPress theme. Ported from LNReader's ifreedom multisrc plugin.
 *
 * The sites run two generations of the theme (ifreedom.su the newer one, bookhamster.ru the older), so most selectors
 * name both.
 */
abstract class Ifreedom(
    override val name: String,
    override val baseUrl: String,
    override val lang: String = "ru",
) : HttpSource() {

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    // Listings

    private fun booksRequest(page: Int, sort: String?, query: String?): Request {
        val url = "$baseUrl/vse-knigi/".toHttpUrl().newBuilder()
        if (sort != null) url.addQueryParameter("sort", sort)
        if (query != null) url.addQueryParameter("searchname", query)
        url.addQueryParameter("bpage", page.toString())
        return GET(url.build(), headers)
    }

    override fun popularMangaRequest(page: Int) = booksRequest(page, SORT_RATING, null)

    override fun popularMangaParse(response: Response) = novelsParse(response)

    override fun latestUpdatesRequest(page: Int) = booksRequest(page, SORT_UPDATED, null)

    override fun latestUpdatesParse(response: Response) = novelsParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) =
        booksRequest(page, if (query.isBlank()) SORT_RATING else null, query.trim().ifEmpty { null })

    override fun searchMangaParse(response: Response) = novelsParse(response)

    private fun novelsParse(response: Response): MangasPage {
        val document = response.asJsoup().checkBlocked(response, baseUrl)
        // The newer theme also shows a slider of finished books above the results.
        val novels = document.select(".one-book-home, .item-book-slide")
            .filter { it.parents().none { parent -> parent.hasClass("main-slider-books") } }
            .mapNotNull { card ->
                val link = card.selectFirst("a[href*=/ranobe/]") ?: return@mapNotNull null
                SManga.create().apply {
                    setUrlWithoutDomain(link.absUrl("href"))
                    title = link.attr("title").ifBlank { card.selectFirst(".block-book-slide-title")?.text().orEmpty() }
                        .ifBlank { card.selectFirst("img")?.attr("alt").orEmpty() }.replace("®", "").trim()
                    thumbnail_url = card.selectFirst("img")?.imageUrl()
                }
            }.distinctBy { it.url }
        val page = response.request.url.queryParameter("bpage")?.toIntOrNull() ?: 1
        val hasNext = document.select("a[href*=bpage=${page + 1}]").isNotEmpty()
        return MangasPage(novels, hasNext)
    }

    // Details

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup().checkBlocked(response, baseUrl)
        return SManga.create().apply {
            title = document.selectFirst("h1")?.text()?.replace("®", "")?.trim().orEmpty()
            thumbnail_url = document.selectFirst(".book-img img, .img-ranobe img")?.imageUrl()
            description = document.selectFirst("[data-name=Описание]")?.select("p")
                ?.map { it.text().trim() }?.filter { it.isNotEmpty() }?.joinToString("\n\n")
                ?: document.selectFirst(".descr-ranobe")?.let(::fullDescription)

            val genres = mutableListOf<String>()
            // Newer theme: rows told apart by their icon. Older theme: a label, then the value.
            document.select(".book-info-list").forEach { row ->
                val icon = row.selectFirst("svg")?.className().orEmpty()
                val value = row.text().trim()
                when {
                    "icon-tabler-tag" in icon -> genres += row.select("a").map { it.text().trim() }
                    "icon-tabler-user" in icon -> if (value != NOT_SET) author = value
                    "icon-tabler-mood-edit" in icon -> if (author == null && value != NOT_SET) author = value
                    "icon-tabler-chart-infographic" in icon -> status = parseStatus(value)
                }
            }
            document.select(".data-ranobe").forEach { row ->
                val label = row.child(0).text().trim()
                val value = row.children().drop(1).joinToString(" ") { it.text() }.trim()
                when (label) {
                    "Жанры" -> genres += row.select("a").map { it.text().trim() }
                    "Автор" -> if (value != NOT_SET) author = value
                    "Статус книги" -> status = parseStatus(value)
                }
            }
            genre = genres.filter { it.isNotEmpty() }.distinct().joinToString().ifEmpty { null }
        }
    }

    /** The older theme cuts the description short and keeps the rest in the "read more" button's onclick. */
    private fun fullDescription(summary: Element): String {
        val full = summary.selectFirst(".open-desc[onclick]")?.attr("onclick")
            ?.let { FULL_DESCRIPTION.find(it)?.groupValues?.get(1) }
            ?.replace("\\'", "'")
        val html = full ?: summary.clone().apply { select(".open-desc").remove() }.html()
        val text = html.replace(BR, "\n").replace(TAG, "")
        return Parser.unescapeEntities(text, false).lines().joinToString("\n") { it.trim() }.trim()
    }

    // Chapters

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup().checkBlocked(response, baseUrl)
        // Newest first, which is the order the app wants.
        return document.select("div.li-ranobe, div.chapterinfo").mapNotNull { row ->
            val link = row.selectFirst("a[href]") ?: return@mapNotNull null
            // A paid chapter links to the wallet, the same page for every chapter, so its id keeps the url unique.
            val locked = row.selectFirst(".buychap") != null || "/koshelek/" in link.attr("href")
            SChapter.create().apply {
                setUrlWithoutDomain(link.absUrl("href") + if (locked) "#${link.attr("data-id")}" else "")
                name = (if (locked) "🔒 " else "") + link.text().trim()
                chapter_number = CHAPTER_NUMBER.find(link.text())?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
                date_upload = row.selectFirst(".li-col2-ranobe, .timechapter")?.text()?.let(::parseDate) ?: 0L
            }
        }
    }

    // Text

    override fun chapterTextParse(response: Response): String {
        val document = response.asJsoup().checkBlocked(response, baseUrl)
        val content = document.selectFirst(".chapter-content, .entry-content")
            ?: throw Exception("No chapter text found. Paid chapters need a purchase on the site.")
        content.select("script, style, ins, noscript, iframe, .pc-adv, .mob-adv, .chapter-setting").remove()
        content.select("img").forEach { img -> img.imageUrl()?.let { img.attr("src", it) } }
        return content.html()
    }

    // Helpers

    private fun parseStatus(text: String): Int {
        val value = text.lowercase()
        return when {
            ONGOING.any { it in value } -> SManga.ONGOING
            COMPLETED.any { it in value } -> SManga.COMPLETED
            HIATUS.any { it in value } -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }

    /** "7 ч. назад", "7 часов назад", "01.05.23" or "22 марта" (this year, or last year if that is still ahead). */
    private fun parseDate(text: String): Long {
        val value = text.trim().lowercase()
        RELATIVE.find(value)?.let { match ->
            val amount = match.groupValues[1].toInt()
            val unit = RELATIVE_UNITS.entries.firstOrNull { (prefix, _) -> match.groupValues[2].startsWith(prefix) }
                ?: return 0L
            return Calendar.getInstance().apply { add(unit.value, -amount) }.timeInMillis
        }
        NUMERIC.matchEntire(value)?.let { match ->
            val (day, month, year) = match.destructured
            val fullYear = year.toInt().let { if (it < 100) it + 2000 else it }
            return Calendar.getInstance().apply { clear(); set(fullYear, month.toInt() - 1, day.toInt()) }.timeInMillis
        }
        DAY_MONTH.matchEntire(value)?.let { match ->
            val month = MONTHS.indexOfFirst { match.groupValues[2].startsWith(it) }.takeIf { it >= 0 } ?: return 0L
            val now = Calendar.getInstance()
            val date = Calendar.getInstance().apply { clear(); set(now.get(Calendar.YEAR), month, match.groupValues[1].toInt()) }
            if (date.after(now)) date.add(Calendar.YEAR, -1)
            return date.timeInMillis
        }
        return 0L
    }

    companion object {
        private const val SORT_RATING = "По рейтингу"
        private const val SORT_UPDATED = "По дате обновления"
        private const val NOT_SET = "Не указан"

        private val FULL_DESCRIPTION = Regex("innerHTML\\s*=\\s*'([\\s\\S]+?)(?<!\\\\)'")
        private val BR = Regex("(?i)<br\\s*/?>")
        private val TAG = Regex("<[^>]+>")
        private val CHAPTER_NUMBER = Regex("(?i)глава\\s*(\\d+(?:\\.\\d+)?)")

        private val ONGOING = listOf("активен", "продолжается", "онгоинг")
        private val COMPLETED = listOf("завершен", "конец", "закончен")
        private val HIATUS = listOf("приостановлен", "заморожен")

        private val RELATIVE = Regex("(\\d+)\\s*(\\p{L}+)\\.?\\s*назад")
        private val RELATIVE_UNITS = linkedMapOf(
            "сек" to Calendar.SECOND,
            "мин" to Calendar.MINUTE,
            "ч" to Calendar.HOUR,
            "дн" to Calendar.DAY_OF_YEAR,
            "д" to Calendar.DAY_OF_YEAR,
            "нед" to Calendar.WEEK_OF_YEAR,
            "мес" to Calendar.MONTH,
            "г" to Calendar.YEAR,
            "л" to Calendar.YEAR,
        )
        private val NUMERIC = Regex("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{2,4})")
        private val DAY_MONTH = Regex("(\\d{1,2})\\s+(\\p{L}+)")
        private val MONTHS = listOf("янв", "фев", "мар", "апр", "ма", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")
    }
}
