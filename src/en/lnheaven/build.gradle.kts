plugins {
    id("yomikku.extension")
}

yomikku {
    name = "LightNovelHeaven"
    className = ".LightNovelHeaven"
    versionCode = 1
    theme("madara")
    source(name = "LightNovelHeaven", lang = "en", baseUrl = "https://lightnovelheaven.com")
}
