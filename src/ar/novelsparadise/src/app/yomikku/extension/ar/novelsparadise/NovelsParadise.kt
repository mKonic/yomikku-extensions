package app.yomikku.extension.ar.novelsparadise

import app.yomikku.multisrc.lightnovelwp.LightNovelWP

class NovelsParadise : LightNovelWP("Novels Paradise", "https://novelsparadise.site", "ar", reverseChapters = true) {
    override val filtersResource = "filters.json"
}
