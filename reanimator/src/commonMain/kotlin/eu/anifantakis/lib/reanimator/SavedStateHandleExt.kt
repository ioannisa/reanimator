package eu.anifantakis.lib.reanimator

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

/**
 * Creates a MutableStateFlow backed by SavedStateHandle with automatic key inference and selective persistence.
 * Combines the power of Kotlin StateFlow with SavedStateHandle persistence.
 *
 * This function is especially useful for MVI architecture patterns where you have a single state object
 * but need certain properties (like loading states) to reset after process death.
 *
 * Usage:
 * ```
 * // Property name "_state" will be used as the key
 * private val _state by savedStateHandle.getMutableStateFlow(
 *     defaultValue = ProductState(),
 *     coroutineScope = viewModelScope,
 *     transientProperties = listOf("isLoading", "errorMessage")
 * )
 * val state = _state.asStateFlow()
 *
 * // Later, update it:
 * _state.update { it.copy(isLoading = true) }
 * ```
 *
 * @param key Optional key to store the serialized value in SavedStateHandle. If null, property name is used.
 * @param defaultValue The default value to use if no value is stored or deserialization fails
 * @param key The key to use for storing the serialized value in SavedStateHandle
 * @param coroutineScope The CoroutineScope to launch flow collection in (typically viewModelScope)
 * @param transientProperties List of property names that should NOT be persisted across process death
 * @return A PropertyDelegateProvider that returns a MutableStateFlow
 */
@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T : Any> SavedStateHandle.getMutableStateFlow(
    defaultValue: T,
    key: String? = null,
    coroutineScope: CoroutineScope,
    transientProperties: Set<String> = emptySet(),
): PropertyDelegateProvider<Any, ReadOnlyProperty<Any, MutableStateFlow<T>>> {
    return PropertyDelegateProvider { owner, property ->

        // build a stable key for SavedStateHandle,
        val viewModelClassName = owner.let { it::class.simpleName } ?: "UnknownViewModel"
        val actualKey = key ?: "${viewModelClassName}_${property.name}"

        val serializer = serializer<T>()
        val json = Json { ignoreUnknownKeys = true }

        // ---------- initial value ----------
        val initial: T = get<String>(actualKey)?.let { stored ->
            if (transientProperties.isEmpty()) {
                // no transients in list, just decode the json
                runCatching { json.decodeFromString(serializer, stored) }
                    .getOrElse { defaultValue }
            } else {
                // transients in the list, merge with defaults
                val saved   = json.parseToJsonElement(stored).jsonObject
                val fresh   = json.encodeToJsonElement(serializer, defaultValue).jsonObject

                // rebuild JSON: keep everything except the transient keys,
                // then add the default values for those keys
                val patched = buildJsonObject {
                    saved.forEach { (k, v) -> if (k !in transientProperties) put(k, v) }
                    transientProperties.forEach { k -> fresh[k]?.let { put(k, it) } }
                }

                runCatching { json.decodeFromJsonElement(serializer, patched) }
                    .getOrElse { defaultValue }
            }
        } ?: defaultValue

        // ---------- Persist each StateFlow emission into SavedStateHandle ----------
        MutableStateFlow(initial).also { flow ->
            flow.onEach { value ->
                // initially encode all properties
                val element = json.encodeToJsonElement(serializer, value)
                //  then remove transient ones if "transientProperties" is not empty
                val cleanedObj = (element as? JsonObject)
                    ?.takeIf { transientProperties.isNotEmpty() }
                    ?.let { JsonObject(it.filterKeys { k -> k !in transientProperties }) }
                    ?: element

                // return the encoded value as SavedStateHandle key/value
                set(actualKey, cleanedObj.toString())
            }.launchIn(coroutineScope)
        }.let { ReadOnlyProperty { _, _ -> it } }
    }
}