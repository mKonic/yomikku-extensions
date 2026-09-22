package app.yomikku.extension.en.eternalune

import app.yomikku.multisrc.madara.Madara

class Eternalune : Madara("Eternalune", "https://eternalune.com", "en", useNewChapterEndpoint = true) {
    override val filtersResource = "filters.json"
}
