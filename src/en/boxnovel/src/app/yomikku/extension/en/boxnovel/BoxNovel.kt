package app.yomikku.extension.en.boxnovel

import app.yomikku.multisrc.madara.Madara

class BoxNovel : Madara("BoxNovel", "https://novelnice.com", "en", useNewChapterEndpoint = true) {
    override val filtersResource = "filters.json"
}
