package app.yomikku.extension.en.foxaholic

import app.yomikku.multisrc.madara.Madara

class Foxaholic : Madara("Foxaholic", "https://www.foxaholic.com", "en", useNewChapterEndpoint = true) {
    override val filtersResource = "filters.json"
}
