package app.yomikku.extension.id.bacalightnovel

import app.yomikku.multisrc.lightnovelwp.LightNovelWP

class BacaLightNovel : LightNovelWP("Baca Light Novel", "https://bacalightnovel.co", "id", reverseChapters = true) {
    override val filtersResource = "filters.json"
}
