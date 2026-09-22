package app.yomikku.extension.pt.centralnovel

import app.yomikku.multisrc.lightnovelwp.LightNovelWP

class CentralNovel : LightNovelWP("Central Novel", "https://centralnovel.com", "pt", reverseChapters = true) {
    override val filtersResource = "filters.json"
}
