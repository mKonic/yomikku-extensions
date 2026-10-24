plugins {
    id("yomikku.extension")
}

yomikku {
    name = "MVLEMPYR"
    className = ".Mvlempyr"
    versionCode = 1
    source(name = "MVLEMPYR", lang = "en", baseUrl = "https://www.mvlempyr.io")
}

dependencies {
    implementation(project(":lib:lnfilters"))
}
