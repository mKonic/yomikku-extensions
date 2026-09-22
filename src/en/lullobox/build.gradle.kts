plugins {
    id("yomikku.extension")
}

yomikku {
    name = "LulloBox"
    className = ".LulloBox"
    versionCode = 1
    theme("madara")
    source(name = "LulloBox", lang = "en", baseUrl = "https://lullobox.com")
}
