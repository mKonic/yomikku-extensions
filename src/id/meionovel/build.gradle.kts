plugins {
    id("yomikku.extension")
}

yomikku {
    name = "MeioNovel"
    className = ".MeioNovel"
    versionCode = 1
    theme("madara")
    source(name = "MeioNovel", lang = "id", baseUrl = "https://meionovels.com")
}
