package app.yomikku.extension.en.dragontea

import app.yomikku.multisrc.madara.Madara
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.text.Normalizer

class DragonTea : Madara("Dragon Tea", "https://dragontea.ink", "en", useNewChapterEndpoint = true) {
    override val filtersResource = "filters.json"

    // Chapters are served with every Latin letter mirrored (a↔z, b↔y, …); accents stay on their letter. The site's
    // own notice above the chapter is plain text.
    override fun cleanChapterText(content: Element) {
        val plain = content.select(".chapter-warning").flatMap { it.getAllElements() }.toSet()
        content.getAllElements().filterNot { it in plain }.flatMap { it.textNodes() }.forEach { node: TextNode ->
            node.text(mirror(node.wholeText))
        }
    }

    private fun mirror(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val mirrored = buildString(decomposed.length) {
            for (c in decomposed) {
                append(
                    when (c) {
                        in 'a'..'z' -> 'z' - (c - 'a')
                        in 'A'..'Z' -> 'Z' - (c - 'A')
                        else -> c
                    },
                )
            }
        }
        return Normalizer.normalize(mirrored, Normalizer.Form.NFC)
    }
}
