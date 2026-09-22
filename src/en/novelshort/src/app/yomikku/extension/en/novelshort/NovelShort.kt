package app.yomikku.extension.en.novelshort

import app.yomikku.multisrc.madara.Madara

class NovelShort : Madara("Novel Short", "https://novel-short.com", "en", useNewChapterEndpoint = true) {
    override val filtersResource = "filters.json"
}
