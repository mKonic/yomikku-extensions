package app.yomikku.extension.en.universalnovel

import app.yomikku.multisrc.lightnovelwp.LightNovelWP

class UniversalNovel : LightNovelWP("Universal Novel", "https://universalnovel.com", "en", reverseChapters = false) {
    override val filtersResource = "filters.json"
}
