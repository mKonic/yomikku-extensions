plugins {
    id("yomikku.extension")
}

yomikku {
    name = "WebNovelOku"
    className = ".WebNovelOku"
    versionCode = 1
    theme("madara")
    source(name = "WebNovelOku", lang = "tr", baseUrl = "https://www.webnoveloku.com")
}
