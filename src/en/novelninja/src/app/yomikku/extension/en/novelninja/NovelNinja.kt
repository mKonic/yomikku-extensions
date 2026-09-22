package app.yomikku.extension.en.novelninja

import app.yomikku.multisrc.madara.Madara

class NovelNinja : Madara("Novel Ninja", "https://novelninja.xyz", "en", useNewChapterEndpoint = true) {
    override val filtersResource = "filters.json"
}
