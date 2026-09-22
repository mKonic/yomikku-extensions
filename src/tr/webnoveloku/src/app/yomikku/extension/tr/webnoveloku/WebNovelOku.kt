package app.yomikku.extension.tr.webnoveloku

import app.yomikku.multisrc.madara.Madara

class WebNovelOku : Madara("WebNovelOku", "https://www.webnoveloku.com", "tr") {
    override val filtersResource = "filters.json"
}
