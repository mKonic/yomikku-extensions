package app.yomikku.extension.en.sleepttls

import app.yomikku.multisrc.madara.Madara

class SleepyTranslations : Madara("SleepyTranslations", "https://sleepytranslations.com", "en", useNewChapterEndpoint = true) {
    override val filtersResource = "filters.json"
}
