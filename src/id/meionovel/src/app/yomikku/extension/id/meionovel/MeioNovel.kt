package app.yomikku.extension.id.meionovel

import app.yomikku.multisrc.madara.Madara

class MeioNovel : Madara("MeioNovel", "https://meionovels.com", "id", useNewChapterEndpoint = true) {
    override val filtersResource = "filters.json"
}
