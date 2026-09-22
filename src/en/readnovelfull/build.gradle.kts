plugins {
    id("yomikku.extension")
}

yomikku {
    name = "ReadNovelFull"
    className = ".ReadNovelFullSource"
    versionCode = 1
    theme("readnovelfull")
    source(name = "ReadNovelFull", lang = "en", baseUrl = "https://readnovelfull.com")
}
