plugins {
    id("yomikku.extension")
}

yomikku {
    name = "FirstKissNovel"
    className = ".FirstKissNovel"
    versionCode = 1
    theme("madara")
    source(name = "FirstKissNovel", lang = "en", baseUrl = "https://1stkissnovel.org")
}
