package app.yomikku.extension.en.requiemtls

import app.yomikku.multisrc.lightnovelwp.LightNovelWP
import org.jsoup.nodes.Element

class RequiemTranslations : LightNovelWP("Requiem Translations", "https://requiemtls.com", "en", reverseChapters = true) {
    override val filtersResource = "filters.json"

    // Paragraphs are served with every character shifted by one of three offsets, picked from the chapter's URL.
    override fun cleanChapterText(content: Element, url: String) {
        val key = url.removeSuffix("/")
        val (lower, cap) = OFFSETS[key.length * key.last().code * 2 % 3]
        content.select("> p").flatMap { it.getAllElements() }.flatMap { it.textNodes() }.forEach { node ->
            node.text(
                node.wholeText.map { c ->
                    val offset = if (c.code in lower + 'A'.code..lower + 'z'.code) lower else cap
                    val decoded = c.code - offset
                    if (decoded in 32..126) decoded.toChar() else c
                }.joinToString(""),
            )
        }
    }

    private companion object {
        val OFFSETS = listOf(12368 to 12462, 6960 to 7054, 4176 to 4270)
    }
}
