plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Lib Read"
    className = ".LibRead"
    versionCode = 1
    theme("readnovelfull")
    source(name = "Lib Read", lang = "en", baseUrl = "https://libread.com")
}
