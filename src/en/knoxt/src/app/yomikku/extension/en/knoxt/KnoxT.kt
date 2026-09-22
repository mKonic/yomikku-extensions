package app.yomikku.extension.en.knoxt

import app.yomikku.multisrc.lightnovelwp.LightNovelWP

class KnoxT : LightNovelWP("KnoxT", "https://knoxt.space", "en", reverseChapters = true) {
    override val filtersResource = "filters.json"
}
