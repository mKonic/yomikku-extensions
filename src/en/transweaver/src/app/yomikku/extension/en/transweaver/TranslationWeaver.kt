package app.yomikku.extension.en.transweaver

import app.yomikku.multisrc.lightnovelwp.LightNovelWP

class TranslationWeaver : LightNovelWP("Translation Weaver", "https://transweaver.com", "en", reverseChapters = true) {
    override val filtersResource = "filters.json"
}
