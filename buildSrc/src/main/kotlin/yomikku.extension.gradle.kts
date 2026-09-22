import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import groovy.json.JsonOutput

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val yomikku = extensions.create<YomikkuExtension>("yomikku", project)
yomikku.packageSuffix.convention("${project.parent!!.name}.${project.name}")


configure<ApplicationExtension> {
    namespace = "app.yomikku.extension"
    configureShared(this)

    sourceSets.named("main") {
        manifest.srcFile(rootProject.file("common/AndroidManifest.xml"))
        java.directories.clear()
        java.directories.add("src")
        kotlin.directories.clear()
        kotlin.directories.add("src")
        res.directories.clear()
        res.directories.add("res")
        resources.directories.clear()
        resources.directories.add("resources")
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("signingkey.jks")
            storePassword = providers.environmentVariable("KEY_STORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("ALIAS").orNull
            keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
        }
    }

    buildTypes {
        named("release") {
            signingConfig = if (rootProject.file("signingkey.jks").exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            // Everything an extension links against is compileOnly, so there is nothing to shrink.
            isMinifyEnabled = false
        }
    }

    dependenciesInfo {
        includeInApk = false
    }

    lint {
        checkReleaseBuilds = false
    }
}

// Everything below depends on what the build script declared in `yomikku { }`, so it runs once the script has.
fun versionCode() = (yomikku.theme?.extensions?.getByType<YomikkuThemeExtension>()?.baseVersionCode?.get() ?: 0) +
    yomikku.versionCode.get()

fun versionName() = "${Versions.LIB_VERSION}.${versionCode()}"

fun packageName() = "app.yomikku.extension.${yomikku.packageSuffix.get()}"

fun nsfwFlag() = if (yomikku.nsfw.get()) 1 else 0

extensions.getByType<ApplicationAndroidComponentsExtension>().finalizeDsl { android ->
    android.defaultConfig.apply {
        applicationId = packageName()
        versionCode = versionCode()
        versionName = versionName()
        manifestPlaceholders["appName"] = "Yomikku: ${yomikku.name.get()}"
        manifestPlaceholders["extClass"] = yomikku.className.get()
        manifestPlaceholders["nsfw"] = nsfwFlag()
    }
}

// What create-repo.py puts in the index for this extension.
val writeExtensionInfo = tasks.register("writeExtensionInfo") {
    val out = layout.buildDirectory.file("extension.json")
    outputs.file(out)
    outputs.upToDateWhen { false }
    doLast {
        val json = JsonOutput.toJson(
            mapOf(
                "name" to "Yomikku: ${yomikku.name.get()}",
                "pkg" to packageName(),
                "versionCode" to versionCode(),
                "versionName" to versionName(),
                "nsfw" to nsfwFlag(),
                "sources" to yomikku.sources.get().map {
                    mapOf("name" to it.name, "lang" to it.lang, "baseUrl" to it.baseUrl, "versionId" to it.versionId)
                },
            ),
        )
        out.get().asFile.writeText(json)
    }
}
tasks.matching { it.name == "assembleRelease" }.configureEach { dependsOn(writeExtensionInfo) }
