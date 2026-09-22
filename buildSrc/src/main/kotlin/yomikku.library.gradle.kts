import com.android.build.api.dsl.LibraryExtension

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

extensions.create<YomikkuThemeExtension>("yomikkuTheme").baseVersionCode.convention(0)

configure<LibraryExtension> {
    namespace = "app.yomikku.lib." + project.name.replace('-', '_')
    configureShared(this)
}
