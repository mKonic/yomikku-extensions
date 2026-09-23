plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Inkitt"
    className = ".Inkitt"
    versionCode = 1
    source(name = "Inkitt", lang = "en", baseUrl = "https://www.inkitt.com")
}

dependencies {
    implementation(project(":lib:lnfilters"))
}
