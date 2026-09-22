package app.yomikku.extension.en.novelfull

import app.yomikku.multisrc.readnovelfull.ReadNovelFull
import app.yomikku.multisrc.readnovelfull.ReadNovelFull.ChapterList

class NovelFull : ReadNovelFull("NovelFull", "https://novelfull.com", "en", chapterList = ChapterList.OPTIONS, latestPage = "latest-release-novel", searchPage = "search", chapterListing = "ajax-chapter-option") {
    override val filtersResource = "filters.json"
}
