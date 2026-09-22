plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Free Web Novel"
    className = ".FreeWebNovel"
    versionCode = 1
    theme("readnovelfull")
    source(name = "Free Web Novel", lang = "en", baseUrl = "https://freewebnovel.com")
}
