package app.yomikku.extension.en.pasteltales

import app.yomikku.multisrc.madara.Madara

class PastelTales : Madara("Pastel Tales", "https://pasteltales.com", "en", useNewChapterEndpoint = true) {
    override val filtersResource = "filters.json"
}
