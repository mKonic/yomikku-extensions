package app.yomikku.extension.ar.hizomanga

import app.yomikku.multisrc.madara.Madara

class HizoManga : Madara("HizoManga", "https://hizomanga.net", "ar", useNewChapterEndpoint = true) {
    override val filtersResource = "filters.json"
}
