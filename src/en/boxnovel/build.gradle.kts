plugins {
    id("yomikku.extension")
}

yomikku {
    name = "BoxNovel"
    className = ".BoxNovel"
    versionCode = 1
    theme("madara")
    source(name = "BoxNovel", lang = "en", baseUrl = "https://novelnice.com")
}
