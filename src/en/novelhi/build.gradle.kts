plugins {
    id("yomikku.extension")
}

yomikku {
    name = "NovelHi"
    className = ".NovelHi"
    versionCode = 1
    source(name = "NovelHi", lang = "en", baseUrl = "https://novelhi.com")
}

dependencies {
    implementation(project(":lib:lnfilters"))
}
