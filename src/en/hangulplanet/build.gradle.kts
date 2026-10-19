plugins {
    id("yomikku.extension")
}

yomikku {
    name = "HangulPlanet"
    className = ".HangulPlanet"
    versionCode = 1
    source(name = "HangulPlanet", lang = "en", baseUrl = "https://hangulplanet.com")
}

dependencies {
    implementation(project(":lib:paced"))
}
