plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Universal Novel"
    className = ".UniversalNovel"
    versionCode = 1
    theme("lightnovelwp")
    source(name = "Universal Novel", lang = "en", baseUrl = "https://universalnovel.com")
}
