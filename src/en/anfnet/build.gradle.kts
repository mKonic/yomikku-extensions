plugins {
    id("yomikku.extension")
}

yomikku {
    name = "AllNovelFull"
    className = ".AllNovelFull"
    versionCode = 1
    theme("readnovelfull")
    source(name = "AllNovelFull", lang = "en", baseUrl = "https://novgo.net")
}
