plugins {
    id("yomikku.extension")
}

yomikku {
    name = "TurkceLightNovels"
    className = ".TurkceLightNovels"
    versionCode = 1
    theme("madara")
    source(name = "TurkceLightNovels", lang = "tr", baseUrl = "https://turkcelightnovels.com")
}
