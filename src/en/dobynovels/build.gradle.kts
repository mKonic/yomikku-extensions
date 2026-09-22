plugins {
    id("yomikku.extension")
}

yomikku {
    name = "DobyNovels"
    className = ".DobyNovels"
    versionCode = 1
    theme("lightnovelwp")
    source(name = "DobyNovels", lang = "en", baseUrl = "https://dobynovels.com")
}
