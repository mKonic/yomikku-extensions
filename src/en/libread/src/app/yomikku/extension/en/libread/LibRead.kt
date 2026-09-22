package app.yomikku.extension.en.libread

import app.yomikku.multisrc.readnovelfull.ReadNovelFull
import app.yomikku.multisrc.readnovelfull.ReadNovelFull.ChapterList

class LibRead : ReadNovelFull("Lib Read", "https://libread.com", "en", chapterList = ChapterList.PAGINATED, latestPage = "sort/latest-novels", searchPage = "search", pageAsPath = true, noPages = listOf("sort/most-popular"), searchKey = "searchkey", postSearch = true) {
    override val filtersResource = "filters.json"
}
