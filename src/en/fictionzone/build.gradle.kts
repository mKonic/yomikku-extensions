plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Fiction Zone"
    className = ".FictionZone"
    versionCode = 1
    source(name = "Fiction Zone", lang = "en", baseUrl = "https://fictionzone.net")
}
