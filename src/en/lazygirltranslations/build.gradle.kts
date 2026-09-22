plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Lazy Girl Translations"
    className = ".LazyGirlTranslations"
    versionCode = 1
    theme("lightnovelwp")
    source(name = "Lazy Girl Translations", lang = "en", baseUrl = "https://lazygirltranslations.com")
}
