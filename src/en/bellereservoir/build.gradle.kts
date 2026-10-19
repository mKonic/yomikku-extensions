plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Belle Repository"
    className = ".BelleRepository"
    versionCode = 1
    theme("madara")
    source(name = "Belle Repository", lang = "en", baseUrl = "https://bellerepository.com")
}
