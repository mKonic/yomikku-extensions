plugins {
    id("yomikku.extension")
}

yomikku {
    name = "NovelLib"
    className = ".NovelLib"
    versionCode = 1
    theme("fictioneer")
    source(name = "NovelLib", lang = "en", baseUrl = "https://novelib.com")
}
