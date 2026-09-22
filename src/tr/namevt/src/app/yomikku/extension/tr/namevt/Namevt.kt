package app.yomikku.extension.tr.namevt

import app.yomikku.multisrc.lightnovelwp.LightNovelWP

class Namevt : LightNovelWP("Namevt", "https://namevt.com", "tr", reverseChapters = true, seriesPath = "seri") {
    override val filtersResource = "filters.json"
}
