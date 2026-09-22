package app.yomikku.extension.en.n1stkissnovel

import app.yomikku.multisrc.madara.Madara

class FirstKissNovel : Madara("FirstKissNovel", "https://1stkissnovel.org", "en", useNewChapterEndpoint = true) {
    override val filtersResource = "filters.json"
}
