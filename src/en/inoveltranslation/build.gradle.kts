plugins {
    id("yomikku.extension")
}

yomikku {
    name = "iNovelTranslation"
    className = ".INovelTranslation"
    versionCode = 1
    source(name = "iNovelTranslation", lang = "en", baseUrl = "https://inoveltranslation.com")
}
