package app.yomikku.extension.ar.arnovel

import app.yomikku.multisrc.madara.Madara

class ArNovel : Madara("ArNovel", "https://ar-no.com", "ar", useNewChapterEndpoint = true) {
    override val filtersResource = "filters.json"
}
