package app.yomikku.extension.tr.turkcelightnovels

import app.yomikku.multisrc.madara.Madara

class TurkceLightNovels : Madara("TurkceLightNovels", "https://turkcelightnovels.com", "tr") {
    override val filtersResource = "filters.json"
}
