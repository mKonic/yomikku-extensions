plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Novel Nice"
    className = ".NovelNice"
    versionCode = 1
    theme("madara")
    source(name = "Novel Nice", lang = "en", baseUrl = "https://novelnice.com")
}
