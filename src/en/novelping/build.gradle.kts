plugins {
    id("yomikku.extension")
}

yomikku {
    name = "NovelPing"
    className = ".NovelPing"
    versionCode = 1
    theme("readnovelfull")
    source(name = "NovelPing", lang = "en", baseUrl = "https://novelping.com")
}
