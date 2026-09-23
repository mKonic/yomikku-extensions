plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Light Novel Translations"
    className = ".LightNovelTranslations"
    versionCode = 1
    source(name = "Light Novel Translations", lang = "en", baseUrl = "https://lightnovelstranslations.com")
}
