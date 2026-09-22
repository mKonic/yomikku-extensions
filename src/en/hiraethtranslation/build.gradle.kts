plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Hiraeth Translation"
    className = ".HiraethTranslation"
    versionCode = 1
    theme("madara")
    source(name = "Hiraeth Translation", lang = "en", baseUrl = "https://hiraethtranslation.com")
}
