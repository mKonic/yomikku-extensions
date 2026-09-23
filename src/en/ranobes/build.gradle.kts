plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Ranobes"
    className = ".RanobesSource"
    versionCode = 1
    theme("ranobes")
    source(name = "Ranobes", lang = "en", baseUrl = "https://ranobes.top")
}
