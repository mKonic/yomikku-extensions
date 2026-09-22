plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Novel Lucky"
    className = ".NovelLucky"
    versionCode = 1
    theme("madara")
    source(name = "Novel Lucky", lang = "th", baseUrl = "https://novel-lucky.com")
}
