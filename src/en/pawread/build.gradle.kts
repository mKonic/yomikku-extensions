plugins {
    id("yomikku.extension")
}

yomikku {
    name = "PawRead"
    className = ".PawRead"
    versionCode = 1
    source(name = "PawRead", lang = "en", baseUrl = "https://m.pawread.com")
}

dependencies {
    implementation(project(":lib:lnfilters"))
}
