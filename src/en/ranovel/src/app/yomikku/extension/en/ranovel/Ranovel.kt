package app.yomikku.extension.en.ranovel

import app.yomikku.multisrc.madara.Madara
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Element

class Ranovel : Madara("Ranovel", "https://ranovel.com", "en", useNewChapterEndpoint = true) {
    override val filtersResource = "filters.json"

    override fun cleanChapterText(content: Element, url: String) {
        content.select("img[alt=\"Buy Me a Coffee at ko-fi.com\"]").forEach { it.parent()?.remove() }
        // The site's watermark: the two nodes after a "Text Ranovel" comment.
        content.getAllElements().flatMap { it.childNodes() }
            .filter { it is Comment && it.data.trim() == "Text Ranovel" }
            .forEach { comment -> repeat(2) { comment.nextSibling()?.remove() } }
    }
}
