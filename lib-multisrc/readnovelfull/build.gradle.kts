plugins {
    id("yomikku.library")
}

yomikkuTheme {
    baseVersionCode = 3
}

dependencies {
    api(project(":lib:lnfilters"))
    api(project(":lib:wpcommon"))
    api(project(":lib:paced"))
}
