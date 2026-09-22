package app.yomikku.extension.en.fannovel

import app.yomikku.multisrc.readwn.Readwn

class FanNovel : Readwn("FanNovel", "https://www.fanmtl.com", "en") {
    override val filtersResource = "filters.json"
}
