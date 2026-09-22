package app.yomikku.extension.id.wbnovel

import app.yomikku.multisrc.madara.Madara

class WBNovel : Madara("WBNovel", "https://wbnovel.com", "id") {
    override val filtersResource = "filters.json"
}
