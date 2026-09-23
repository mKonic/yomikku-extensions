plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Novel Hall"
    className = ".NovelHall"
    versionCode = 1
    source(name = "Novel Hall", lang = "en", baseUrl = "https://www.novelhall.com")
}
