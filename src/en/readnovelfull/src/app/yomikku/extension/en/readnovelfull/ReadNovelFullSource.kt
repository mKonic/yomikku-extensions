package app.yomikku.extension.en.readnovelfull

import app.yomikku.multisrc.readnovelfull.ReadNovelFull

class ReadNovelFullSource : ReadNovelFull("ReadNovelFull", "https://readnovelfull.com", "en", latestPage = "novel-list/latest-release-novel", searchPage = "novel-list/search") {
    override val filtersResource = "filters.json"
}
