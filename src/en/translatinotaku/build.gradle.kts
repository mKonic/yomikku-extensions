plugins {
    id("yomikku.extension")
}

yomikku {
    name = "TranslatinOtaku"
    className = ".TranslatinOtaku"
    versionCode = 1
    theme("madara")
    source(name = "TranslatinOtaku", lang = "en", baseUrl = "https://translatinotaku.net")
}
