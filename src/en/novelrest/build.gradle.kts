plugins {
    id("yomikku.extension")
}

yomikku {
    name = "NovelRest"
    className = ".NovelRest"
    versionCode = 1
    source(name = "NovelRest", lang = "en", baseUrl = "https://novelrest.vercel.app")
}

dependencies {
    implementation(project(":lib:lnfilters"))
}
