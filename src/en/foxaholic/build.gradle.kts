plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Foxaholic"
    className = ".Foxaholic"
    versionCode = 1
    theme("madara")
    source(name = "Foxaholic", lang = "en", baseUrl = "https://www.foxaholic.com")
}
