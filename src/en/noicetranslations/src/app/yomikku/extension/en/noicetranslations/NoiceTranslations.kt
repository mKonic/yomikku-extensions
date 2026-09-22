package app.yomikku.extension.en.noicetranslations

import app.yomikku.multisrc.madara.Madara

class NoiceTranslations : Madara("Noice Translations", "https://noicetranslations.com", "en", useNewChapterEndpoint = true) {
    override val filtersResource = "filters.json"
}
