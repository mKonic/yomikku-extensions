plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Novel Fire"
    className = ".NovelFireSource"
    versionCode = 1
    theme("novelfire")
    source(name = "Novel Fire", lang = "en", baseUrl = "https://novelfire.net")
}
