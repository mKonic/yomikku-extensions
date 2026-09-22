package app.yomikku.extension.en.etudetranslations

import app.yomikku.multisrc.madara.Madara

class EtudeTranslations : Madara("Etude Translations", "https://etudetranslations.com", "en", useNewChapterEndpoint = true) {
    override val filtersResource = "filters.json"
}
