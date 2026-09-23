plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Crimson Scrolls"
    className = ".CrimsonScrolls"
    versionCode = 1
    source(name = "Crimson Scrolls", lang = "en", baseUrl = "https://crimsonscrolls.net")
}

dependencies {
    implementation(project(":lib:paced"))
}
