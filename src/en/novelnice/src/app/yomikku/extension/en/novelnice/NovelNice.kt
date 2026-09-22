package app.yomikku.extension.en.novelnice

import app.yomikku.multisrc.madara.Madara

class NovelNice : Madara("Novel Nice", "https://novelnice.com", "en", useNewChapterEndpoint = true) {
    override val filtersResource = "filters.json"
}
