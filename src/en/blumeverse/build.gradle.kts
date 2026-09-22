plugins {
    id("yomikku.extension")
}

yomikku {
    name = "BlumeVerse"
    className = ".BlumeVerse"
    versionCode = 1
    theme("lightnovelwp")
    source(name = "BlumeVerse", lang = "en", baseUrl = "https://blume-verse.com")
}
