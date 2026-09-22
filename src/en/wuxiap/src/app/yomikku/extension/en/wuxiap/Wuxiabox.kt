package app.yomikku.extension.en.wuxiap

import app.yomikku.multisrc.readwn.Readwn

class Wuxiabox : Readwn("Wuxiabox", "https://www.wuxiabox.com", "en") {
    override val filtersResource = "filters.json"
}
