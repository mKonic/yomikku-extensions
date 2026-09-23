plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Royal Road"
    className = ".RoyalRoad"
    versionCode = 1
    source(name = "Royal Road", lang = "en", baseUrl = "https://www.royalroad.com")
}

dependencies {
    implementation(project(":lib:lnfilters"))
}
