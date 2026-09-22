plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Novel Ninja"
    className = ".NovelNinja"
    versionCode = 1
    theme("madara")
    source(name = "Novel Ninja", lang = "en", baseUrl = "https://novelninja.xyz")
}
