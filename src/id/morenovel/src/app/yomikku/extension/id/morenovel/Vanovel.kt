package app.yomikku.extension.id.morenovel

import app.yomikku.multisrc.madara.Madara

class Vanovel : Madara("Vanovel", "https://vanovel.com", "id", useNewChapterEndpoint = true) {
    override val filtersResource = "filters.json"
}
