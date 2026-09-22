plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Novel Phoenix"
    className = ".NovelPhoenix"
    versionCode = 1
    theme("novelfire")
    source(name = "Novel Phoenix", lang = "en", baseUrl = "https://novelphoenix.com")
}
