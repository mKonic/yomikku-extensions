package app.yomikku.extension.en.sonicmtl

import app.yomikku.multisrc.madara.Madara

class SonicMTL : Madara("SonicMTL", "https://www.sonicmtl.com", "en", useNewChapterEndpoint = true) {
    override val filtersResource = "filters.json"
}
