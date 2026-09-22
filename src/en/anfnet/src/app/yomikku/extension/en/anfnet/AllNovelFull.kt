package app.yomikku.extension.en.anfnet

import app.yomikku.multisrc.readnovelfull.ReadNovelFull
import app.yomikku.multisrc.readnovelfull.ReadNovelFull.ChapterList

class AllNovelFull : ReadNovelFull("AllNovelFull", "https://novgo.net", "en", chapterList = ChapterList.OPTIONS, latestPage = "latest-release-novel", searchPage = "search", chapterListing = "ajax-chapter-option") {
    override val filtersResource = "filters.json"
}
