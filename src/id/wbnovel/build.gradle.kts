plugins {
    id("yomikku.extension")
}

yomikku {
    name = "WBNovel"
    className = ".WBNovel"
    versionCode = 1
    theme("madara")
    source(name = "WBNovel", lang = "id", baseUrl = "https://wbnovel.com")
}
