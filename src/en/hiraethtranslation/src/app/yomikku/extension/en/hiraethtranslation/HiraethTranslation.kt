package app.yomikku.extension.en.hiraethtranslation

import app.yomikku.multisrc.madara.Madara

class HiraethTranslation : Madara("Hiraeth Translation", "https://hiraethtranslation.com", "en", useNewChapterEndpoint = true) {
    override val filtersResource = "filters.json"
}
