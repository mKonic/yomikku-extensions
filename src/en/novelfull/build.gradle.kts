plugins {
    id("yomikku.extension")
}

yomikku {
    name = "NovelFull"
    className = ".NovelFull"
    versionCode = 1
    theme("readnovelfull")
    source(name = "NovelFull", lang = "en", baseUrl = "https://novelfull.com")
}
