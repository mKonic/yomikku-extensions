package app.yomikku.extension.en.novelping

import app.yomikku.multisrc.readnovelfull.ReadNovelFull

/** NovelPing, formerly Novel Arrow (LNReader's novelarrow plugin), now on the novelfull template. */
class NovelPing : ReadNovelFull("NovelPing", "https://novelping.com", "en", latestPage = "sort/updates", searchPage = "search") {
    override val filtersResource = "filters.json"
}
