plugins {
    id("yomikku.extension")
}

yomikku {
    name = "SonicMTL"
    className = ".SonicMTL"
    versionCode = 1
    theme("madara")
    source(name = "SonicMTL", lang = "en", baseUrl = "https://www.sonicmtl.com")
}
