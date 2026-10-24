plugins {
    id("yomikku.extension")
}

yomikku {
    name = "KDT Novels"
    className = ".KdtNovels"
    versionCode = 1
    source(name = "KDT Novels", lang = "en", baseUrl = "https://kdtnovels.net")
}

dependencies {
    implementation(project(":lib:lnfilters"))
}
