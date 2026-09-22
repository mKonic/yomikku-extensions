plugins {
    id("yomikku.extension")
}

yomikku {
    name = "DaoTranslate"
    className = ".DaoTranslate"
    versionCode = 1
    theme("lightnovelwp")
    source(name = "DaoTranslate", lang = "en", baseUrl = "https://daotranslate.com")
}
