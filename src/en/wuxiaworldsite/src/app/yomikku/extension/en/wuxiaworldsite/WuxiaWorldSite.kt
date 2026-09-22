package app.yomikku.extension.en.wuxiaworldsite

import app.yomikku.multisrc.madara.Madara

class WuxiaWorldSite : Madara("WuxiaWorld.Site", "https://wuxiaworld.site", "en", useNewChapterEndpoint = true) {
    override val filtersResource = "filters.json"
}
