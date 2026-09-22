plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Requiem Translations"
    className = ".RequiemTranslations"
    versionCode = 1
    theme("lightnovelwp")
    source(name = "Requiem Translations", lang = "en", baseUrl = "https://requiemtls.com")
}
