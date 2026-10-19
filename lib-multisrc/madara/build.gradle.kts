plugins {
    id("yomikku.library")
}

yomikkuTheme {
    baseVersionCode = 5
}

dependencies {
    api(project(":lib:lnfilters"))
    api(project(":lib:wpcommon"))
}
