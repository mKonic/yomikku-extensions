package app.yomikku.extension.en.bellereservoir

import app.yomikku.multisrc.madara.Madara
import org.jsoup.nodes.Element

class BelleRepository : Madara("Belle Repository", "https://bellerepository.com", "en", useNewChapterEndpoint = true) {
    override val filtersResource = "filters.json"

    // Each chapter opens with a box sending readers to the translator's Patreon.
    override fun cleanChapterText(content: Element, url: String) {
        content.select(".chapter-warning:has(a[href*=patreon.com]), p:contains(read all available chapters here)").remove()
    }
}
