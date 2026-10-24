plugins {
    id("yomikku.extension")
}

yomikku {
    name = "Re:Library"
    className = ".ReLibrary"
    versionCode = 1
    source(name = "Re:Library", lang = "en", baseUrl = "https://re-library.com")
}

dependencies {
    implementation(project(":lib:wpcommon"))
}
