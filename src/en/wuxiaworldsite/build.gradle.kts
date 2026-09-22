plugins {
    id("yomikku.extension")
}

yomikku {
    name = "WuxiaWorld.Site"
    className = ".WuxiaWorldSite"
    versionCode = 1
    theme("madara")
    source(name = "WuxiaWorld.Site", lang = "en", baseUrl = "https://wuxiaworld.site")
}
