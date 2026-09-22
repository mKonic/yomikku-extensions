package app.yomikku.extension.en.asuralightnovel

import app.yomikku.multisrc.madara.Madara

class SrankManga : Madara("Srank Manga", "https://srankmanga.com", "en", useNewChapterEndpoint = true) {
    override val filtersResource = "filters.json"
}
