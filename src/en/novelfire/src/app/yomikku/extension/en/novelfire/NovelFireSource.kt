package app.yomikku.extension.en.novelfire

import app.yomikku.multisrc.novelfire.NovelFire

class NovelFireSource : NovelFire("Novel Fire", "https://novelfire.net", "en") {
    override val filtersResource = "filters.json"
}
