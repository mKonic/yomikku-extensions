plugins {
    id("yomikku.extension")
}

yomikku {
    name = "WorldNovel"
    className = ".WorldNovel"
    versionCode = 1
    theme("madara")
    source(name = "WorldNovel", lang = "fr", baseUrl = "https://world-novel.fr")
}
