plugins {
    id("yomikku.library")
}

yomikkuTheme {
    baseVersionCode = 1
}

dependencies {
    api(project(":lib:wpcommon"))
}
