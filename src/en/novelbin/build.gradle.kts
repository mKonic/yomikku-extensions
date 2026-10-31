plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Novel Bin"
    className = ".NovelBin"
    versionCode = 1
    source(name = "Novel Bin", lang = "en", baseUrl = "https://novelbin.xyz")
}

dependencies {
    implementation(project(":lib:lnfilters"))
}
