package app.yomikku.extension.en.lnheaven

import app.yomikku.multisrc.madara.Madara

class LightNovelHeaven : Madara("LightNovelHeaven", "https://lightnovelheaven.com", "en", useNewChapterEndpoint = true) {
    override val filtersResource = "filters.json"
}
