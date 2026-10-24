package app.yomikku.extension.en.bakatsuki

import app.yomikku.lib.lnfilters.LnFilters
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Baka-Tsuki, a MediaWiki of fan translations. Ported from LNReader's bakaTsuki plugin; everything goes through the
 * wiki's api.php. The catalogue is the wiki's own "Light novel (English)" category, read once with the status
 * categories beside it. A project page's links, in document order, are its chapters.
 */
class BakaTsuki : HttpSource() {

    override val name = "Baka-Tsuki"
    override val baseUrl = "https://www.baka-tsuki.org/project"
    override val lang = "en"
    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }

    override fun getFilterList(): FilterList = LnFilters.fromResource(javaClass, "filters.json")

    // API

    private suspend fun query(vararg params: Pair<String, String>): JsonObject {
        val url = "$baseUrl/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
            .apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }
            .build()
        val body = client.newCall(GET(url, headers)).awaitSuccess().body.string()
        val reply = json.parseToJsonElement(body).jsonObject
        reply.obj("error")?.let { throw Exception("Baka-Tsuki API error: ${it.text("info")}") }
        return reply
    }

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.array(key: String) = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    private suspend fun categoryMembers(category: String): List<String> {
        val titles = mutableListOf<String>()
        var next: String? = null
        do {
            val reply = query(
                "action" to "query", "list" to "categorymembers", "cmtitle" to "Category:$category",
                "cmnamespace" to "0", "cmtype" to "page", "cmlimit" to "500",
                *(next?.let { arrayOf("cmcontinue" to it) } ?: emptyArray()),
            )
            reply.obj("query")?.array("categorymembers")?.mapNotNullTo(titles) { it.text("title") }
            next = reply.obj("continue")?.text("cmcontinue")
        } while (next != null)
        return titles
    }

    // Page titles use underscores in URLs and spaces in the API; manga and chapter urls are wiki links.
    private fun pathOf(title: String) = "/index.php?" + "$baseUrl/index.php".toHttpUrl().newBuilder()
        .addQueryParameter("title", title.replace(' ', '_')).build().encodedQuery

    private fun titleOf(path: String) = (baseUrl + path).toHttpUrl().queryParameter("title").orEmpty().replace('_', ' ')

    // Catalogue

    private val catalogueLock = Mutex()
    private var catalogue: List<String>? = null
    private var catalogueSet: Set<String> = emptySet()
    private val statusByTitle = mutableMapOf<String, String>()
    private val contentCache = mutableMapOf<String, Boolean>()

    private suspend fun catalogue(): List<String> = catalogueLock.withLock {
        catalogue ?: coroutineScope {
            val titles = async { categoryMembers(CATALOGUE_CATEGORY).filterNot { SUB_PAGE.containsMatchIn(it) } }
            // Read for status only: these categories are language-agnostic and would pull in translation siblings.
            val statuses = STATUS_CATEGORIES.map { (category, status) -> async { categoryMembers(category) to status } }
            for ((members, status) in statuses.awaitAll()) {
                members.forEach { statusByTitle.putIfAbsent(it, status) }
            }
            titles.await().distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        }.also {
            catalogue = it
            catalogueSet = it.toSet()
        }
    }

    /** [titles] as novels, with cover thumbnails; the API takes 50 titles a request. */
    private suspend fun withCovers(titles: List<String>): List<SManga> {
        val covers = mutableMapOf<String, String>()
        for (batch in titles.chunked(50)) {
            // Covers are cosmetic; a failed batch keeps the placeholder.
            runCatching {
                val reply = query(
                    "action" to "query", "titles" to batch.joinToString("|"), "prop" to "pageimages",
                    "piprop" to "thumbnail", "pithumbsize" to "300", "pilimit" to "50",
                )
                for (page in reply.obj("query")?.array("pages").orEmpty()) {
                    val source = page.obj("thumbnail")?.text("source") ?: continue
                    covers[page.text("title") ?: continue] = source
                }
            }
        }
        return titles.map { title ->
            SManga.create().apply {
                url = pathOf(title)
                this.title = title
                thumbnail_url = covers[title]
            }
        }
    }

    // Listings

    override suspend fun getPopularManga(page: Int) = browse(page, getFilterList())

    override suspend fun getLatestUpdates(page: Int) = page(latestTitles(), page)

    /** Recently edited pages, chapter edits rolled up to their project, padded with the rest of the catalogue. */
    private suspend fun latestTitles(): List<String> {
        val catalogue = catalogue()
        val changes = query(
            "action" to "query", "list" to "recentchanges", "rcnamespace" to "0", "rclimit" to "500",
            "rctype" to "edit|new", "rcprop" to "title",
        ).obj("query")?.array("recentchanges").orEmpty().mapNotNull { it.text("title") }
        val ordered = LinkedHashSet<String>()
        for (title in changes) {
            val parent = title.substringBefore(':').trim()
            when {
                title in catalogueSet -> ordered += title
                parent in catalogueSet -> ordered += parent
            }
        }
        ordered += catalogue
        return ordered.toList()
    }

    private suspend fun browse(page: Int, filters: FilterList): MangasPage {
        val params = LnFilters.queryParams(filters).toMap()
        var titles = catalogue()
        params["status"]?.let { status -> titles = titles.filter { statusByTitle[it] == status } }
        if (params["order"] == "desc") titles = titles.reversed()
        return page(titles, page)
    }

    private suspend fun page(titles: List<String>, page: Int): MangasPage {
        val slice = titles.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE)
        return MangasPage(withCovers(slice), page * PAGE_SIZE < titles.size)
    }

    // Search

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val term = query.trim()
        if (term.isEmpty()) return browse(page, filters)
        catalogue()
        if (page > 1) {
            // Deeper pages fall through to the wiki's full-text search; the ranked catalogue matches fill page one.
            val titles = query(
                "action" to "query", "list" to "search", "srsearch" to term, "srnamespace" to "0",
                "srlimit" to PAGE_SIZE.toString(), "sroffset" to ((page - 1) * PAGE_SIZE).toString(),
            ).obj("query")?.array("search").orEmpty().mapNotNull { it.text("title") }.filter(::isNovelTitle)
            return MangasPage(withCovers(dropEmptyProjects(titles)), titles.isNotEmpty())
        }
        val scored = mutableMapOf<String, Int>()
        for (title in catalogue()) score(title, term).takeIf { it > 0 }?.let { scored[title] = it }
        // The wiki's own searches add what the catalogue missed, ranked a little lower.
        val remote = coroutineScope {
            val prefix = async {
                runCatching {
                    query("action" to "query", "list" to "prefixsearch", "pssearch" to term, "psnamespace" to "0", "pslimit" to "50")
                        .obj("query")?.array("prefixsearch").orEmpty().mapNotNull { it.text("title") }
                }.getOrDefault(emptyList())
            }
            val fullText = async {
                runCatching {
                    query("action" to "query", "list" to "search", "srsearch" to term, "srnamespace" to "0", "srlimit" to "50")
                        .obj("query")?.array("search").orEmpty().mapNotNull { it.text("title") }
                }.getOrDefault(emptyList())
            }
            prefix.await() + fullText.await()
        }
        for (title in remote) {
            if (!isNovelTitle(title) || title in scored) continue
            score(title, term).takeIf { it > 0 }?.let { scored[title] = it - 50 }
        }
        val ranked = scored.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(PAGE_SIZE).map { it.key }
        return MangasPage(withCovers(dropEmptyProjects(ranked)), true)
    }

    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "").lowercase().replace(NON_ALPHANUMERIC, " ").trim()

    /** Title-first relevance; the wiki's full-text search would bury a project page under its own chapters. */
    private fun score(title: String, term: String): Int {
        val a = normalize(title)
        val b = normalize(term)
        if (a.isEmpty() || b.isEmpty()) return 0
        if (a == b) return 1000
        if (a.startsWith(b)) return 900 - minOf(a.length - b.length, 99)
        if (b in a) return 800 - minOf(a.length - b.length, 99)
        val queryTokens = b.split(' ').filter { it.isNotEmpty() }
        val titleTokens = a.split(' ').filter { it.isNotEmpty() }
        val matched = queryTokens.count { token -> titleTokens.any { it.startsWith(token) } }
        if (matched == queryTokens.size) return 700 - minOf(a.length, 99)
        if (matched > 0) return Math.round(matched.toFloat() / queryTokens.size * 500)
        return 0
    }

    /**
     * Chapter pages share the main namespace with projects. Catalogue membership is the reliable test; outside it,
     * any colon marks a sub-page and a trailing parenthesis a translation sibling ("Absolute Duo (Swedish)").
     */
    private fun isNovelTitle(title: String): Boolean {
        if (title in catalogueSet) return true
        if (':' in title || TRAILING_PARENTHESIS.containsMatchIn(title)) return false
        return !SUB_PAGE.containsMatchIn(title) && !NON_ENGLISH.containsMatchIn(title)
    }

    /**
     * Drops project pages with nothing behind them. Catalogue entries are trusted unless they are on the suspicion
     * list; those and outside hits get one cheap probe each, at most [PROBE_BUDGET] a search.
     */
    private suspend fun dropEmptyProjects(titles: List<String>): List<String> {
        var probes = 0
        val verdicts = coroutineScope {
            titles.map { title ->
                when {
                    title in catalogueSet && title !in POSSIBLY_EMPTY -> null
                    contentCache[title] != null -> null
                    probes >= PROBE_BUDGET -> null
                    else -> {
                        probes++
                        async { title to hasReadableContent(title) }
                    }
                }
            }.mapNotNull { it?.await() }.toMap()
        }
        return titles.filter { verdicts[it] ?: contentCache[it] ?: true }
    }

    private suspend fun hasReadableContent(title: String): Boolean = try {
        // The short form also covers projects that file their chapters under a shortened prefix.
        val prefix = title.substringBefore(':').trim() + ":"
        val pages = query("action" to "query", "list" to "allpages", "apprefix" to prefix, "apnamespace" to "0", "aplimit" to "1")
            .obj("query")?.array("allpages").orEmpty()
        pages.isNotEmpty().also { contentCache[title] = it }
    } catch (_: Exception) {
        true // never hide a novel because a probe failed
    }

    override fun popularMangaRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = throw UnsupportedOperationException()
    override fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()

    // Details

    override suspend fun getMangaDetails(manga: SManga): SManga {
        catalogue()
        val page = query(
            "action" to "query", "titles" to titleOf(manga.url), "prop" to "extracts|pageimages|categories",
            "explaintext" to "1", "exsectionformat" to "raw", "piprop" to "thumbnail", "pithumbsize" to "400",
            "cllimit" to "max", "clshow" to "!hidden", "redirects" to "1",
        ).obj("query")?.array("pages")?.firstOrNull()
        return SManga.create().apply {
            title = titleOf(manga.url)
            if (page == null || page["missing"] != null) {
                description = "This project is not available on Baka-Tsuki."
                return@apply
            }
            val title = page.text("title") ?: title
            this.title = title
            thumbnail_url = page.obj("thumbnail")?.text("source")
            description = extractSummary(page.text("extract"))
            val categories = page.array("categories").mapNotNull { it.text("title") }
            genre = categories.mapNotNull { GENRE.find(it)?.groupValues?.get(1)?.trim() }.joinToString().ifEmpty { null }
            val status = categories.firstNotNullOfOrNull { STATUS_CATEGORIES[it.removePrefix("Category:")] } ?: statusByTitle[title]
            this.status = when (status) {
                ONGOING -> SManga.ONGOING
                COMPLETED -> SManga.COMPLETED
                ON_HIATUS -> SManga.ON_HIATUS
                INACTIVE -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
            author = resolveAuthor(categories)
        }
    }

    /**
     * Project pages open with wiki housekeeping, so the lead is rarely the synopsis: prefer a section titled like one,
     * else the lead's first paragraphs that aren't boilerplate.
     */
    private fun extractSummary(extract: String?): String? {
        if (extract == null) return null
        // A split with the headings kept: [lead, heading, body, heading, body, ...].
        val sections = mutableListOf<String>()
        var last = 0
        for (match in SECTION_HEADING.findAll(extract)) {
            sections += extract.substring(last, match.range.first)
            sections += match.groupValues[1]
            last = match.range.last + 1
        }
        sections += extract.substring(last)
        for (i in 1 until sections.size step 2) {
            if (!SYNOPSIS_HEADING.containsMatchIn(sections[i])) continue
            cleanParagraphs(sections.getOrNull(i + 1))?.let { return it }
        }
        return cleanParagraphs(sections[0])
    }

    private fun cleanParagraphs(text: String?): String? {
        val kept = text?.split(Regex("\n+"))?.map { it.replace(WHITESPACE, " ").trim() }
            ?.filter { it.length > 40 && !BOILERPLATE.containsMatchIn(it) }
        if (kept.isNullOrEmpty()) return null
        val summary = kept.joinToString("\n\n")
        return if (summary.length > 1500) summary.take(1497) + "..." else summary
    }

    /**
     * Projects are filed under a category named after the author, among imprints and structural tags. An omitted
     * author is correct and a guessed one is not, so anything that doesn't look like a personal name is skipped.
     */
    private fun resolveAuthor(categories: List<String>) = categories.firstNotNullOfOrNull { category ->
        val name = category.removePrefix("Category:").trim()
        name.takeIf {
            !GENRE.containsMatchIn(category) && !NON_AUTHOR_CATEGORY.containsMatchIn(it) &&
                it.none(Char::isDigit) && it.split(WHITESPACE).size >= 2
        }
    }

    override fun mangaDetailsParse(response: Response): SManga = throw UnsupportedOperationException()

    // Chapters

    private class Candidate(val title: String, val name: String, val volume: Int?, val isFullText: Boolean)

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val novelTitle = titleOf(manga.url)
        val html = parse(novelTitle) ?: return emptyList()
        // Chapters aren't always filed under the full title: "Anohana: The Flower We Saw That Day" keeps them at
        // "Anohana:Part 1 Chapter 1".
        val shortTitle = novelTitle.substringBefore(':').trim()
        val prefixes = listOfNotNull("$novelTitle:", "$shortTitle:".takeIf { shortTitle.isNotEmpty() && shortTitle != novelTitle })
        val seen = mutableSetOf<String>()
        // Document order is reading order; the API's page lists are alphabetical (Chapter 10 before Chapter 2).
        val candidates = Jsoup.parseBodyFragment(html).select("a[href]").mapNotNull { anchor ->
            if (anchor.hasClass("new") || anchor.hasClass("external")) return@mapNotNull null
            val href = anchor.attr("href")
            if (EXTERNAL_HREF.containsMatchIn(href) || FILE_HREF.containsMatchIn(href) || "action=edit" in href) return@mapNotNull null
            val rawTitle = anchor.attr("title").trim()
            if (rawTitle.isEmpty() || rawTitle.endsWith("(page does not exist)")) return@mapNotNull null
            val title = rawTitle.replace('_', ' ')
            val prefix = prefixes.firstOrNull { title.startsWith(it) } ?: return@mapNotNull null
            if (SPECIAL_NAMESPACE.containsMatchIn(title)) return@mapNotNull null
            val label = anchor.text().replace(WHITESPACE, " ").trim()
            val suffix = title.substring(prefix.length).trim()
            // Project scaffolding (registration, staff pages) shares the chapters' prefix.
            if (NON_CHAPTER.containsMatchIn(suffix) || !seen.add(title)) return@mapNotNull null
            Candidate(
                title = title,
                name = label.ifEmpty { suffix },
                volume = VOLUME.find(suffix)?.groupValues?.get(1)?.toIntOrNull(),
                // A whole-volume page is often titled "...:Volume 1" and only its link says "Full Text".
                isFullText = FULL_TEXT.containsMatchIn(suffix) || FULL_TEXT.matches(label),
            )
        }
        // A volume's Full Text page repeats its chapters; keep it only where the volume has nothing else.
        val chapterVolumes = candidates.filterNot { it.isFullText }.map { it.volume }.toSet()
        val kept = candidates.filter { !it.isFullText || it.volume !in chapterVolumes }
        // Volumes ascending, then everything ungrouped; document order within each.
        val ordered = kept.withIndex().sortedWith(compareBy({ it.value.volume ?: Int.MAX_VALUE }, { it.index })).map { it.value }
        val dates = releaseDates(ordered.map { it.title })
        return ordered.mapIndexed { index, candidate ->
            SChapter.create().apply {
                url = pathOf(candidate.title)
                name = candidate.name
                chapter_number = (index + 1).toFloat()
                scanlator = candidate.volume?.let { "Volume $it" } ?: "Other"
                date_upload = dates[candidate.title] ?: 0L
            }
        }.reversed()
    }

    /**
     * Last-edit times, 50 titles a request and at most [MAX_DATE_BATCHES] requests: Baka-Tsuki is a small community
     * server. Best effort.
     */
    private suspend fun releaseDates(titles: List<String>): Map<String, Long> {
        val dates = mutableMapOf<String, Long>()
        for (batch in titles.chunked(50).take(MAX_DATE_BATCHES)) {
            val pages = runCatching {
                query("action" to "query", "titles" to batch.joinToString("|"), "prop" to "revisions", "rvprop" to "timestamp")
                    .obj("query")?.array("pages").orEmpty()
            }.getOrNull() ?: break
            for (page in pages) {
                val timestamp = page.array("revisions").firstOrNull()?.text("timestamp") ?: continue
                val date = runCatching { TIMESTAMP.parse(timestamp)?.time }.getOrNull() ?: continue
                dates[page.text("title") ?: continue] = date
            }
        }
        return dates
    }

    private suspend fun parse(title: String): String? = runCatching {
        query(
            "action" to "parse", "page" to title, "prop" to "text", "disableeditsection" to "1", "disabletoc" to "1",
            "redirects" to "1",
        ).obj("parse")?.text("text")
    }.getOrNull()

    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

    // Text

    override suspend fun getChapterText(chapter: SChapter): String {
        // A deleted or renamed page is an API error, not an empty body.
        val html = parse(titleOf(chapter.url)) ?: throw Exception("This chapter is not available on Baka-Tsuki.")
        val document = Jsoup.parseBodyFragment(html, "https://www.baka-tsuki.org/")
        document.select(
            ".mw-editsection, #toc, .toc, .navbox, .printfooter, .catlinks, .mw-jump-link, .noprint, #siteSub, " +
                ".mw-empty-elt, #contentSub, script, style",
        ).remove()
        // Chapter navigation is a table at the top and bottom of most pages, not always in the same place.
        document.select("table").filter { NAVIGATION.containsMatchIn(it.text()) }.forEach { it.remove() }
        // Gallery images are thumb.php thumbnails about 120 px wide, and the reader never upscales: swap in the
        // original files, whose URLs the API gives 50 at a time.
        val images = document.select("img[src]")
        val files = images.associateWith { image ->
            image.absUrl("src").toHttpUrlOrNull()?.takeIf { it.encodedPath.endsWith("/thumb.php") }?.queryParameter("f")
        }
        val originals = originalUrls(files.values.filterNotNull().distinct())
        for ((image, file) in files) {
            val src = file?.let { originals[it.replace('_', ' ')] } ?: image.absUrl("src")
            image.attr("src", src).removeAttr("srcset").removeAttr("width").removeAttr("height")
        }
        document.select("a[href]").forEach { it.attr("href", it.absUrl("href")) }
        return document.body().html().trim().ifEmpty { throw Exception("This chapter appears to be empty.") }
    }

    /** File name (with spaces) to the original's URL; best effort, a failed batch keeps the thumbnails. */
    private suspend fun originalUrls(files: List<String>): Map<String, String> {
        val urls = mutableMapOf<String, String>()
        for (batch in files.chunked(50)) {
            val pages = runCatching {
                query(
                    "action" to "query", "titles" to batch.joinToString("|") { "File:$it" }, "prop" to "imageinfo",
                    "iiprop" to "url",
                ).obj("query")?.array("pages").orEmpty()
            }.getOrNull() ?: continue
            for (page in pages) {
                val url = page.array("imageinfo").firstOrNull()?.text("url") ?: continue
                urls[page.text("title")?.removePrefix("File:") ?: continue] = url
            }
        }
        return urls
    }

    override fun chapterTextParse(response: Response): String = throw UnsupportedOperationException()

    companion object {
        private const val PAGE_SIZE = 40
        private const val PROBE_BUDGET = 12
        private const val MAX_DATE_BATCHES = 4
        private const val CATALOGUE_CATEGORY = "Light novel (English)"

        // filters.json carries these status names, LNReader's NovelStatus values.
        private const val ONGOING = "Ongoing"
        private const val COMPLETED = "Completed"
        private const val ON_HIATUS = "On Hiatus"
        private const val INACTIVE = "Inactive"
        private val STATUS_CATEGORIES = mapOf(
            "Active Projects" to ONGOING,
            "Completed Project" to COMPLETED,
            "Stalled Projects" to ON_HIATUS,
            "Inactive Projects" to INACTIVE,
            "Hosted Projects" to ONGOING,
        )

        /** Catalogue entries that had no sub-pages when LNReader's plugin last surveyed them; re-checked once a session. */
        private val POSSIBLY_EMPTY = setOf(
            "Allison", "Ark", "Clotaku Club!", "CtG—Zero Kara Sodateru Dennou Shoujo", "Etsusa Bridge", "Famima!",
            "Hikaru ga Chikyuu ni Itakoro......", "Hitotsu no Tairiki no Monogatari",
            "I'm a High School Boy and a Bestselling Light Novel author, strangled by my female classmate who is my junior and a voice actress",
            "Kamisu Reina Series", "Kill No More", "Lillia to Treize", "Maru-MA", "Meg to Seron", "Mushi to Medama",
            "Ojamajo Doremi 16", "Onii-chan Dakedo Ai Sae Areba Kankei Nai yo ne—",
            "Ore ga Ojou-Sama Gakkou ni ‘Shomin Sample’ Toshite Usarareta Ken",
            "Ore no Kanojo to Osananajimi ga Shuraba Sugiru ~Brazilian Portuguese~",
            "Ore no Nounai Sentakushi ga, Gakuen Love Come o Zenryoku de Jama Shiteiru", "Puppetmaster",
            "Remembrances for a certain pilot", "Sayonara Piano Sonata", "Sekai Ichi no Imouto-sama", "Sword of the Emperor",
            "Tabi ni Deyou, Horobiyuku Sekai no Hate Made", "Tsuki Tsuki!", "Tsukumodo Antique Shop",
            "Una Simple Revisión en Español", "Vamp!",
            "We Don't Open Anywhere -There are no facts, only interpretations.-", "White Album 2 Omake",
        )

        private val SUB_PAGE = Regex(
            """:\s*(?:Volume|Vol\.?|Chapter|Part|Book|Full[_ ]Text|Illustrations?|Prologue|Epilogue|Afterword|""" +
                """Side[_ ]Stor(?:y|ies)|Short[_ ]Stor(?:y|ies)|Names[_ ]and[_ ]Terminology|Registration|Registry|Updates?|""" +
                """Staff|Guidelines|Format|Historique|Enregistrement)""",
            RegexOption.IGNORE_CASE,
        )
        private val NON_CHAPTER = Regex(
            """^(?:Registration|Registry|Archives?|Staff|Guidelines?|Updates?|Format|Names[\s_]and[\s_]Terminology|""" +
                """Terminology|Translation|Translators?|Editors?|Discussion|Sandbox|Talk|Status|References?|Credits?|""" +
                """To-?do|Feedback|Preview|Announcements?)""",
            RegexOption.IGNORE_CASE,
        )
        private val NON_ENGLISH = Regex(
            """(?:^|[\s(\[~\-_])(?:fran[cç]ais|espa[nñ]ol|spanish|french|german|deutsch|italian|italiano|polski|polish|""" +
                """portugu[eê]s|portuguese|brazilian|russian|swedish|svenska|indonesia|indonesian|vietnamese|chinese|thai|""" +
                """turkish|t[uü]rk[cç]e|arabic|korean|japanese|dutch|nederlands|hungarian|magyar|romanian|greek|hebrew|""" +
                """persian|farsi|czech|bulgarian|serbian|croatian|finnish|danish|norwegian|ukrainian|latvian|lithuanian|""" +
                """estonian|slovak|slovenian|catalan|filipino|tagalog|malay|hindi|bengali|tamil|urdu)(?:$|[\s)\]~\-_:])|""" +
                """[\s\-~_](?:FR|PL|ES|DE|IT|PT|RU|VN|CN|TH|TR|AR|KR|JP|NL|HU|RO|GR|CZ|BG|RS|HR|FI|DK|NO|UA|SE|ID)$""",
            RegexOption.IGNORE_CASE,
        )
        private val GENRE = Regex("""^Category:Genre\s*-\s*(.+)$""", RegexOption.IGNORE_CASE)
        private val NON_AUTHOR_CATEGORY = Regex(
            """^(?:Light novel|Web novel|Original light novel|Visual novel|Audio novel|Genre|Hosted|Active|Completed|""" +
                """Inactive|Stalled|Teaser|Licensed|Pages? |Candidates|Articles|Project|Series|Novel|Manga)|""" +
                """(?:Bunko|Books|Publishing|Shoten|Shuppan|Kadokawa|Shueisha|Kodansha|Shogakukan|ASCII|Media Factory|""" +
                """Enterbrain|Hobby Japan|Overlap|SoftBank|Fujimi|Dengeki|Gagaga|Sneaker|Ichijinsha|Earth Star|""" +
                """Micro Magazine|TO Books|Alphapolis|Famitsu|Fantasia|Dash|Kobunsha|Takeshobo|Houbunsha|Media Works)""",
            RegexOption.IGNORE_CASE,
        )
        private val BOILERPLATE = Regex(
            """^(?:only available to registered users|register here|please read|this project|the project|as of |""" +
                """note:|warning:|attention|status:|translation|editing|recruit)|available in the following languages|""" +
                """this project has been|has been (?:restored|licensed|removed|discontinued|taken down)|do you (?:follow|want)|""" +
                // Contributor notices, the terminology page link and the dated update log under the synopsis.
                """^(?:those who wish to contribute|translators are asked|every chapter)|names and terminology|""" +
                """^(?:January|February|March|April|May|June|July|August|September|October|November|December) \d{1,2},? \d{4}""",
            RegexOption.IGNORE_CASE,
        )
        private val SECTION_HEADING = Regex("""^\s*=+\s*(.+?)\s*=+\s*$""", RegexOption.MULTILINE)
        private val SYNOPSIS_HEADING = Regex("synopsis|summary|story|plot|description|about", RegexOption.IGNORE_CASE)
        private val TRAILING_PARENTHESIS = Regex("""\([^)]+\)\s*$""")
        private val SPECIAL_NAMESPACE = Regex("^(?:File|Image|Category|Template|Help|User|Talk|Special):", RegexOption.IGNORE_CASE)
        private val EXTERNAL_HREF = Regex("^(?:https?:)?//")
        private val FILE_HREF = Regex("""\.(?:pdf|epub|mobi|zip|rar|7z|docx?)$""", RegexOption.IGNORE_CASE)
        private val VOLUME = Regex("""(?:Volume|Vol\.?)[\s_]*(\d+)""", RegexOption.IGNORE_CASE)
        private val FULL_TEXT = Regex("""Full[\s_]*Text""", RegexOption.IGNORE_CASE)
        private val NAVIGATION = Regex("Back to|Return to|Forward to|Main Page", RegexOption.IGNORE_CASE)
        private val COMBINING_MARKS = Regex("[̀-ͯ]")
        private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
        private val WHITESPACE = Regex("""\s+""")
        private val TIMESTAMP = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
