plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Srank Manga"
    className = ".SrankManga"
    versionCode = 1
    theme("madara")
    source(name = "Srank Manga", lang = "en", baseUrl = "https://srankmanga.com")
}
