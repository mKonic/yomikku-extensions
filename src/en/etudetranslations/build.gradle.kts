plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Etude Translations"
    className = ".EtudeTranslations"
    versionCode = 1
    theme("madara")
    source(name = "Etude Translations", lang = "en", baseUrl = "https://etudetranslations.com")
}
