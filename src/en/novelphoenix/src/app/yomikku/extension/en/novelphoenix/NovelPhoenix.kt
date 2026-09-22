package app.yomikku.extension.en.novelphoenix

import app.yomikku.multisrc.novelfire.NovelFire

class NovelPhoenix : NovelFire("Novel Phoenix", "https://novelphoenix.com", "en") {
    override val filtersResource = "filters.json"
}
