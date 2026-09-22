package app.yomikku.extension.th.novellucky

import app.yomikku.multisrc.madara.Madara

class NovelLucky : Madara("Novel Lucky", "https://novel-lucky.com", "th", useNewChapterEndpoint = true) {
    override val filtersResource = "filters.json"
}
