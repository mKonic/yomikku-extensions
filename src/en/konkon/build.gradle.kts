plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Konkon"
    className = ".Konkon"
    versionCode = 1
    nsfw = true
    source(name = "Konkon", lang = "en", baseUrl = "https://konkon.ink")
}
