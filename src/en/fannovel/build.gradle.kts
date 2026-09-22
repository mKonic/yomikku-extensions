plugins {
    id("yomikku.extension")
}

yomikku {
    name = "FanNovel"
    className = ".FanNovel"
    versionCode = 1
    theme("readwn")
    source(name = "FanNovel", lang = "en", baseUrl = "https://www.fanmtl.com")
}
