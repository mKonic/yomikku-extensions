plugins {
    id("yomikku.library")
}

yomikkuTheme {
    baseVersionCode = 6
}

dependencies {
    api(project(":lib:lnfilters"))
    api(project(":lib:wpcommon"))
    testImplementation(kotlin("stdlib"))
    testImplementation(libs.jsoup)
    testImplementation("junit:junit:4.13.2")
}
