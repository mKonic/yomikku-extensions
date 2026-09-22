
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import java.io.Serializable
import javax.inject.Inject

/**
 * What an extension's build script declares about it:
 *
 * ```
 * yomikku {
 *     name = "Royal Road"
 *     className = ".RoyalRoad"
 *     versionCode = 1
 *     source(name = "Royal Road", lang = "en", baseUrl = "https://www.royalroad.com")
 * }
 * ```
 *
 * The sources listed here only describe the extension in the repository index. The source classes report their own
 * name, language and address to the app, and `scripts/create-repo.py` checks the two agree.
 */
abstract class YomikkuExtension @Inject constructor(private val project: Project) {
    /** Name shown for the extension. */
    abstract val name: Property<String>

    /** Source class, or a [SourceFactory][eu.kanade.tachiyomi.source.SourceFactory], relative to the package. */
    abstract val className: Property<String>

    /** Bumped on every change to the extension. */
    abstract val versionCode: Property<Int>

    abstract val nsfw: Property<Boolean>

    /** Package suffix, defaulting to `<lang>.<directory>`. */
    abstract val packageSuffix: Property<String>

    abstract val sources: ListProperty<SourceInfo>

    init {
        nsfw.convention(false)
        sources.convention(emptyList())
    }

    /** The lib-multisrc theme this extension is built on, if any. */
    var theme: Project? = null
        private set

    fun theme(name: String) {
        val themeProject = project.evaluationDependsOn(":lib-multisrc:$name")
        theme = themeProject
        project.dependencies.add("implementation", themeProject)
    }

    fun source(name: String, lang: String, baseUrl: String, versionId: Int = 1) {
        sources.add(SourceInfo(name, lang, baseUrl, versionId))
    }

    data class SourceInfo(val name: String, val lang: String, val baseUrl: String, val versionId: Int) : Serializable
}

/** A theme's own version, added to every extension that uses it so a theme change updates them all. */
abstract class YomikkuThemeExtension {
    abstract val baseVersionCode: Property<Int>
}

