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
        restoreOrder(content)
        content.getAllElements().flatMap { it.textNodes() }.forEach { node ->
            val text = node.wholeText
            val cleaned = watermark.replace(text, "")
            if (cleaned != text) node.text(cleaned)
        }
    }

    /**
     * Some chapters arrive with their paragraphs shuffled, the right order given by `data-reader-order-index` (or a
     * CSS `order`) and put back by the site's script. Sorted here instead, each paragraph into the slots the shuffled
     * ones held.
     */
    private fun restoreOrder(content: Element) {
        val articles = content.select("#article[data-reader-order=pending]") +
            listOfNotNull(content.takeIf { it.id() == "article" && it.attr("data-reader-order") == "pending" })
        articles.distinct().forEach { article ->
            val kids = article.children().toList()
            val items = kids.mapIndexedNotNull { slot, node ->
                if (node.hasAttr("data-reader-order-anchor")) return@mapIndexedNotNull null
                val index = node.attr("data-reader-order-index").takeIf { node.hasAttr("data-reader-order-index") }
                    ?: node.takeIf { it.normalName() in setOf("p", "h4", "img") }
                        ?.let { CSS_ORDER.find(it.attr("style"))?.groupValues?.get(1) }
                index?.toIntOrNull()?.let { Triple(slot, it, node) }
            }
            if (items.isEmpty()) return@forEach
            val placeholders = items.map { (slot) -> Element("i").also { kids[slot].replaceWith(it) } }
            items.sortedBy { it.second }.forEachIndexed { k, (_, _, node) ->
                node.removeAttr("data-reader-order-index")
                val style = CSS_ORDER.replace(node.attr("style"), "")
                if (style.isBlank()) node.removeAttr("style") else node.attr("style", style)
                placeholders[k].replaceWith(node)
            }
        }
    }

    companion object {
        private val CSS_ORDER = Regex("""(?:^|;)\s*order\s*:\s*(\d+)\s*(?:!important)?\s*;?""", RegexOption.IGNORE_CASE)
    }
}
