package app.yomikku.extension.fr.worldnovel

import app.yomikku.multisrc.madara.Madara

class WorldNovel : Madara("WorldNovel", "https://world-novel.fr", "fr", useNewChapterEndpoint = true) {
    override val filtersResource = "filters.json"
}
