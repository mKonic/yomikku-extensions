plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Chikari"
    className = ".Chikari"
    versionCode = 1
    source(name = "Chikari", lang = "en", baseUrl = "https://chikari.moe")
}

dependencies {
    implementation(project(":lib:lnfilters"))
    implementation(project(":lib:paced"))
}
