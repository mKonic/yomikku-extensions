plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Wuxiabox"
    className = ".Wuxiabox"
    versionCode = 1
    theme("readwn")
    source(name = "Wuxiabox", lang = "en", baseUrl = "https://www.wuxiabox.com")
}
