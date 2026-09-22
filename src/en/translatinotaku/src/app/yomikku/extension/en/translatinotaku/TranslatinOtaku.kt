package app.yomikku.extension.en.translatinotaku

import app.yomikku.multisrc.madara.Madara

class TranslatinOtaku : Madara("TranslatinOtaku", "https://translatinotaku.net", "en", useNewChapterEndpoint = true) {
    override val filtersResource = "filters.json"
}
