plugins {
    id("yomikku.extension")
}

yomikku {
    name = "HizoManga"
    className = ".HizoManga"
    versionCode = 1
    theme("madara")
    source(name = "HizoManga", lang = "ar", baseUrl = "https://hizomanga.net")
}
