plugins {
    id("yomikku.library")
}

yomikkuTheme {
    baseVersionCode = 2
}

dependencies {
    api(project(":lib:wpcommon"))
    api(project(":lib:paced"))
}
