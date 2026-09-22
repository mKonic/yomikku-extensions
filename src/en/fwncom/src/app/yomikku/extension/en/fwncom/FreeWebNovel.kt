package app.yomikku.extension.en.fwncom

import app.yomikku.multisrc.readnovelfull.ReadNovelFull
import app.yomikku.multisrc.readnovelfull.ReadNovelFull.ChapterList
import org.jsoup.nodes.Element

class FreeWebNovel : ReadNovelFull(
    "Free Web Novel",
    "https://freewebnovel.com",
    "en",
    chapterList = ChapterList.POST_API,
    chapterListing = "api/chapterlist.php",
    latestPage = "sort/latest-release",
    searchPage = "search",
    pageAsPath = true,
    noPages = listOf("sort/most-popular"),
) {
    override val filtersResource = "filters.json"

    // The site's name is stamped into chapters spelled with look-alike characters ("𝐟𝐫𝐞𝐞𝐰𝐞𝐛𝐧𝐨𝐯𝐞𝐥.𝐜𝐨𝐦").
    private val watermark by lazy {
        Regex(javaClass.getResourceAsStream("/watermark.regex")!!.bufferedReader().use { it.readText() })
    }

    override fun cleanChapterText(content: Element, url: String) {
        content.getAllElements().flatMap { it.textNodes() }.forEach { node ->
            val text = node.wholeText
            val cleaned = watermark.replace(text, "")
            if (cleaned != text) node.text(cleaned)
        }
    }
}
