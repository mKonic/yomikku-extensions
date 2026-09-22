package app.yomikku.extension.ar.kolnovel

import app.yomikku.multisrc.lightnovelwp.LightNovelWP
import org.jsoup.nodes.Element

class KolNovel : LightNovelWP("Kol Novel", "https://kolnovel.com", "ar", reverseChapters = true) {
    override val filtersResource = "filters.json"

    // Watermark paragraphs are hidden with classes the page declares in a <style> under the article.
    override fun cleanChapterText(content: Element, url: String) {
        val css = content.ownerDocument()?.select("article > style")?.joinToString("\n") { it.data() }.orEmpty()
        Regex("\\.(\\w+)(?=\\s*[,{])").findAll(css).map { it.groupValues[1] }.toSet().forEach { cls ->
            content.select("p.$cls").remove()
        }
    }
}
