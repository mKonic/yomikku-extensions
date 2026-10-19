plugins {
    id("yomikku.extension")
}

yomikku {
    name = "WTR-LAB"
    className = ".WtrLab"
    versionCode = 2
    source(name = "WTR-LAB", lang = "en", baseUrl = "https://wtr-lab.com")
}

dependencies {
    implementation(project(":lib:lnfilters"))
}
