plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Central Novel"
    className = ".CentralNovel"
    versionCode = 1
    theme("lightnovelwp")
    source(name = "Central Novel", lang = "pt", baseUrl = "https://centralnovel.com")
}
