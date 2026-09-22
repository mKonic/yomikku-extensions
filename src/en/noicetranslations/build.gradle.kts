plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Noice Translations"
    className = ".NoiceTranslations"
    versionCode = 1
    theme("madara")
    source(name = "Noice Translations", lang = "en", baseUrl = "https://noicetranslations.com")
}
