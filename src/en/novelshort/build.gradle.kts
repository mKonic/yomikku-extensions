plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Novel Short"
    className = ".NovelShort"
    versionCode = 1
    theme("madara")
    source(name = "Novel Short", lang = "en", baseUrl = "https://novel-short.com")
}
