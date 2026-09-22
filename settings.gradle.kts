pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://www.jitpack.io")
    }
}

rootProject.name = "yomikku-extensions"

include(":lib-api")

File(rootDir, "lib").listFiles().orEmpty().filter { it.isDirectory }.sorted().forEach {
    include(":lib:${it.name}")
}

// Every directory under lib-multisrc is a theme, and every src/<lang>/<name> an extension.
File(rootDir, "lib-multisrc").listFiles().orEmpty().filter { it.isDirectory }.sorted().forEach {
    include(":lib-multisrc:${it.name}")
}
File(rootDir, "src").listFiles().orEmpty().filter { it.isDirectory }.sorted().forEach { lang ->
    lang.listFiles().orEmpty().filter { File(it, "build.gradle.kts").exists() }.sorted().forEach {
        include(":src:${lang.name}:${it.name}")
    }
}
