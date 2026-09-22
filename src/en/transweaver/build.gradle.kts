plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Translation Weaver"
    className = ".TranslationWeaver"
    versionCode = 1
    theme("lightnovelwp")
    source(name = "Translation Weaver", lang = "en", baseUrl = "https://transweaver.com")
}
