package app.yomikku.lib.lnfilters

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Filters described the way LNReader plugins describe them: a JSON object of `key -> {type, label, value, options}`,
 * where the key is the query parameter the site expects. Ported plugins keep their filter definitions as data and
 * this turns them into [Filter]s and back into query parameters.
 */
object LnFilters {

    private val json = Json { ignoreUnknownKeys = true }

    /** Reads the definitions in [definitions], a JSON object as LNReader's plugins and multisrc filters store it. */
    fun parse(definitions: String): FilterList {
        val root = json.parseToJsonElement(definitions).jsonObject
        val filters = (root["filters"]?.jsonObject ?: root).map { (key, value) -> filter(key, value.jsonObject) }
        return FilterList(filters)
    }

    /** Loads definitions shipped as a Java resource of the extension, or no filters if there are none. */
    fun fromResource(owner: Class<*>, path: String): FilterList {
        val text = owner.classLoader?.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
            ?: return FilterList()
        return parse(text)
    }

    private fun filter(key: String, definition: JsonObject): Filter<*> {
        val label = definition["label"]?.jsonPrimitive?.contentOrNull ?: key
        val options = definition["options"]?.jsonArray.orEmpty().map { option ->
            val obj = option.jsonObject
            Option(obj["label"]!!.jsonPrimitive.content, obj["value"]!!.jsonPrimitive.content)
        }
        val value = definition["value"]
        return when (definition["type"]?.jsonPrimitive?.content) {
            "Picker" -> Picker(key, label, options, (value as? JsonPrimitive)?.contentOrNull.orEmpty())
            "Checkbox" -> CheckboxGroup(key, label, options, (value as? JsonArray).orEmpty().strings())
            "XCheckbox" -> {
                val obj = value as? JsonObject
                ExcludableGroup(
                    key,
                    label,
                    options,
                    (obj?.get("include") as? JsonArray).orEmpty().strings(),
                    (obj?.get("exclude") as? JsonArray).orEmpty().strings(),
                )
            }
            "Switch" -> Switch(key, label, (value as? JsonPrimitive)?.booleanOrNull ?: false)
            else -> Text(key, label, (value as? JsonPrimitive)?.contentOrNull.orEmpty())
        }
    }

    private fun List<kotlinx.serialization.json.JsonElement>.strings() = map { it.jsonPrimitive.content }.toSet()

    /**
     * Query parameters for [filters] the way most WordPress novel themes take them: a picker or text sends its value
     * when it has one, a checkbox group sends one parameter per checked box, a switch sends `true` when on. Excludable
     * groups are left out, as every site names their two lists differently; read them with [ExcludableGroup].
     */
    fun queryParams(filters: FilterList): List<Pair<String, String>> = filters.flatMap { filter ->
        when (filter) {
            is Picker -> listOfNotNull(filter.selected.takeIf { it.isNotEmpty() }?.let { filter.key to it })
            is Text -> listOfNotNull(filter.state.takeIf { it.isNotBlank() }?.let { filter.key to it })
            is Switch -> if (filter.state) listOf(filter.key to "true") else emptyList()
            is CheckboxGroup -> filter.state.filter { it.state }.map { filter.key to it.value }
            else -> emptyList()
        }
    }

    data class Option(val label: String, val value: String)

    class Picker(val key: String, name: String, val options: List<Option>, default: String) :
        Filter.Select<String>(
            name,
            options.map { it.label }.toTypedArray(),
            options.indexOfFirst { it.value == default }.coerceAtLeast(0),
        ) {
        val selected: String get() = options.getOrNull(state)?.value.orEmpty()
    }

    class Text(val key: String, name: String, default: String) : Filter.Text(name, default)

    class Switch(val key: String, name: String, default: Boolean) : Filter.CheckBox(name, default)

    class Box(name: String, val value: String, state: Boolean) : Filter.CheckBox(name, state)

    class CheckboxGroup(val key: String, name: String, options: List<Option>, checked: Set<String>) :
        Filter.Group<Box>(name, options.map { Box(it.label, it.value, it.value in checked) })

    class Tri(name: String, val value: String, state: Int) : Filter.TriState(name, state)

    class ExcludableGroup(
        val key: String,
        name: String,
        options: List<Option>,
        included: Set<String>,
        excluded: Set<String>,
    ) : Filter.Group<Tri>(
        name,
        options.map {
            Tri(
                it.label,
                it.value,
                when (it.value) {
                    in included -> Filter.TriState.STATE_INCLUDE
                    in excluded -> Filter.TriState.STATE_EXCLUDE
                    else -> Filter.TriState.STATE_IGNORE
                },
            )
        },
    ) {
        val included: List<String> get() = state.filter { it.isIncluded() }.map { it.value }
        val excluded: List<String> get() = state.filter { it.isExcluded() }.map { it.value }
    }
}
