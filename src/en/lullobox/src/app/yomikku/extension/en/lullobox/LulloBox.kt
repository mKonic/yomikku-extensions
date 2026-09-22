package app.yomikku.extension.en.lullobox

import app.yomikku.multisrc.madara.Madara

class LulloBox : Madara("LulloBox", "https://lullobox.com", "en", useNewChapterEndpoint = true) {
    override val filtersResource = "filters.json"
}
