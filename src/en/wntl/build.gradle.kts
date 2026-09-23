plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Web Novel Translation"
    className = ".Wntl"
    versionCode = 1
    source(name = "Web Novel Translation", lang = "en", baseUrl = "https://wntl.net")
}
