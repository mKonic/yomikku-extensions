plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Baka-Tsuki"
    className = ".BakaTsuki"
    versionCode = 1
    source(name = "Baka-Tsuki", lang = "en", baseUrl = "https://www.baka-tsuki.org/project")
}

dependencies {
    implementation(project(":lib:lnfilters"))
}
