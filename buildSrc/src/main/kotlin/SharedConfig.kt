import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

internal fun Project.catalogLib(alias: String) =
    extensions.getByType<VersionCatalogsExtension>().named("libs").findLibrary(alias).get()

/**
 * Android and Kotlin settings every module shares, and the libraries the app provides at runtime. Those are
 * compileOnly: the app's class loader already has them, and a bundled copy would shadow the app's.
 */
internal fun Project.configureShared(android: CommonExtension) {
    android.compileSdk = Versions.COMPILE_SDK
    android.defaultConfig.minSdk = Versions.MIN_SDK
    android.compileOptions.apply {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    dependencies {
        "compileOnly"(catalogLib("kotlin-stdlib"))
        "compileOnly"(catalogLib("coroutines-core"))
        "compileOnly"(catalogLib("serialization-json"))
        "compileOnly"(catalogLib("okhttp"))
        "compileOnly"(catalogLib("jsoup"))
        "compileOnly"(catalogLib("jspecify"))
        "compileOnly"(catalogLib("injekt"))
        "compileOnly"(catalogLib("preference"))
        if (path != ":lib-api") "compileOnly"(project(":lib-api"))
    }
}
