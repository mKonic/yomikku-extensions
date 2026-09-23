plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Novel7s"
    className = ".Novel7s"
    versionCode = 1
    source(name = "Novel7s", lang = "en", baseUrl = "https://novel7s.com")
}

dependencies {
    implementation(project(":lib:lnfilters"))
    implementation(project(":lib:paced"))
}
