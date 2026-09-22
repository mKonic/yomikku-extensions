package app.yomikku.extension.ar.riwyat

import app.yomikku.multisrc.madara.Madara
import org.jsoup.nodes.Element

class Riwyat : Madara("Riwyat", "https://cenele.com", "ar", useNewChapterEndpoint = true) {
    override val filtersResource = "filters.json"

    // Invisible text the site scatters through chapters to spoil copies.
    override fun cleanChapterText(content: Element) {
        content.select("span[style*=\"opacity: 0; position: fixed;\"], [role=presentation]").remove()
    }
}
