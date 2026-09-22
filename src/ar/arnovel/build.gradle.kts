plugins {
    id("yomikku.extension")
}

yomikku {
    name = "ArNovel"
    className = ".ArNovel"
    versionCode = 1
    theme("madara")
    source(name = "ArNovel", lang = "ar", baseUrl = "https://ar-no.com")
}
