plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Kol Novel"
    className = ".KolNovel"
    versionCode = 1
    theme("lightnovelwp")
    source(name = "Kol Novel", lang = "ar", baseUrl = "https://kolnovel.com")
}
