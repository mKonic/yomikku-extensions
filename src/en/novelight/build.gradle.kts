plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Novelight"
    className = ".Novelight"
    versionCode = 1
    source(name = "Novelight", lang = "en", baseUrl = "https://novelight.net")
}

dependencies {
    implementation(project(":lib:lnfilters"))
}
