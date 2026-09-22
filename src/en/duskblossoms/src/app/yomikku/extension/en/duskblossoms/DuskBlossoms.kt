package app.yomikku.extension.en.duskblossoms

import app.yomikku.multisrc.madara.Madara

class DuskBlossoms : Madara("Dusk Blossoms", "https://duskblossoms.com", "en", useNewChapterEndpoint = true) {
    override val filtersResource = "filters.json"
}
