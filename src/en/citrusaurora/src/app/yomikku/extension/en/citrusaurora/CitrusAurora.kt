package app.yomikku.extension.en.citrusaurora

import app.yomikku.multisrc.madara.Madara

class CitrusAurora : Madara("Citrus Aurora", "https://citrusaurora.com", "en", useNewChapterEndpoint = true) {
    override val filtersResource = "filters.json"
}
