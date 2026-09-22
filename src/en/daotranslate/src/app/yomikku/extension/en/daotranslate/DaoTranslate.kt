package app.yomikku.extension.en.daotranslate

import app.yomikku.multisrc.lightnovelwp.LightNovelWP

class DaoTranslate : LightNovelWP("DaoTranslate", "https://daotranslate.com", "en", reverseChapters = true) {
    override val filtersResource = "filters.json"
}
