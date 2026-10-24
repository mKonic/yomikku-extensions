plugins {
    id("yomikku.extension")
}

yomikku {
    name = "NovelBuddy"
    className = ".NovelBuddy"
    versionCode = 2
    source(name = "NovelBuddy", lang = "en", baseUrl = "https://novelbuddy.me")
}

dependencies {
    implementation(project(":lib:lnfilters"))
}
