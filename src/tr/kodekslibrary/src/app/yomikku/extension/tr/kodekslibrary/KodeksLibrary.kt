package app.yomikku.extension.tr.kodekslibrary

import app.yomikku.multisrc.lightnovelwp.LightNovelWP

class KodeksLibrary : LightNovelWP("Kodeks Library", "https://www.kodekslibrary.com", "tr", reverseChapters = true) {
    override val filtersResource = "filters.json"
}
