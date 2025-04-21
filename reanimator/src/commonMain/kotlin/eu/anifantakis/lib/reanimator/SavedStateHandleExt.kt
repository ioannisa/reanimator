package eu.anifantakis.lib.reanimator

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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
 * @param coroutineScope The CoroutineScope to launch flow collection in (typically viewModelScope)
 * @param transientProperties List of property names that should NOT be persisted across process death
 * @param json The Json instance to use for serialization/deserialization
 * @return A PropertyDelegateProvider that returns a MutableStateFlow
 */
inline fun <reified T : Any> SavedStateHandle.getMutableStateFlow(
    defaultValue: T,
    coroutineScope: CoroutineScope,
    key: String? = null,
    transientProperties: List<String> = emptyList(),
    // Default Json instance now ignores unknown keys for better robustness
    json: Json = Json { ignoreUnknownKeys = true }
): PropertyDelegateProvider<Any, ReadOnlyProperty<Any, MutableStateFlow<T>>> {
    return PropertyDelegateProvider { _, property ->
        val actualKey = key ?: property.name

        // Create a serializable MutableStateFlow with selective persistence
        val stateFlow = createSerializableStateFlow(
            savedStateHandle = this, // Pass SavedStateHandle instance
            key = actualKey,
            defaultValue = defaultValue,
            coroutineScope = coroutineScope,
            transientProperties = transientProperties,
            json = json
        )

        // Return a read-only property delegate that provides the StateFlow
        ReadOnlyProperty { _, _ -> stateFlow }
    }
}

/**
 * Internal helper method that creates a MutableStateFlow with serialization and selective persistence.
 * This is the core implementation that handles the complex logic of:
 * 1. Retrieving and deserializing state from SavedStateHandle
 * 2. Handling transient properties during restoration
 * 3. Setting up automatic persistence of state changes back to SavedStateHandle
 * 4. Filtering out transient properties during serialization
 *
 * This function should not be called directly - use getMutableStateFlow() instead.
 *
 * @param key Key to store the serialized value under in SavedStateHandle
 * @param defaultValue The default value to use if no value is stored or deserialization fails
 * @param coroutineScope The CoroutineScope to launch flow collection in
 * @param transientProperties List of property names that should NOT be persisted across process death
 * @param json The Json instance to use for serialization/deserialization
 * @return A MutableStateFlow that automatically persists changes to SavedStateHandle
 */
@OptIn(ExperimentalSerializationApi::class)
@PublishedApi
internal inline fun <reified T : Any> createSerializableStateFlow(
    savedStateHandle: SavedStateHandle,
    key: String,
    defaultValue: T,
    coroutineScope: CoroutineScope,
    transientProperties: List<String>,
    json: Json
): MutableStateFlow<T> {
    val serializer: KSerializer<T> = serializer<T>()

    // Get initial value from SavedStateHandle or use default
    val savedString = savedStateHandle.get<String>(key)
    val initialValue: T = if (savedString != null) {
        try {
            if (transientProperties.isEmpty()) {
                // No transient properties, deserialize directly
                json.decodeFromString(serializer, savedString)
            } else {
                // --- Optimized Transient Property Restoration ---
                val savedJsonElement = json.parseToJsonElement(savedString)

                // Lazily encode default value only if needed for merging
                val defaultJsonElement by lazy { json.encodeToJsonElement(serializer, defaultValue) }

                // We can only merge if BOTH saved state and default state serialize to JsonObject
                if (savedJsonElement is JsonObject && defaultJsonElement is JsonObject) {
                    try {
                        val defaultJsonObject = defaultJsonElement.jsonObject // Access lazy val

                        // Create a new JSON object by merging saved state and default transient properties
                        val mergedJsonObject = buildJsonObject {
                            // Copy all properties from the saved state
                            savedJsonElement.jsonObject.forEach { (propName, propValue) ->
                                put(propName, propValue)
                            }
                            // Reset transient properties using values from the default state's object
                            transientProperties.forEach { propName ->
                                if (defaultJsonObject.containsKey(propName)) {
                                    // Use the value from the default object
                                    put(propName, defaultJsonObject[propName] ?: JsonPrimitive(null))
                                }
                            }
                        }
                        // Deserialize the merged state ONCE
                        json.decodeFromJsonElement(serializer, mergedJsonObject)

                    } catch (e: Exception) {
                        // Handle potential errors during merge/decode (e.g., property type mismatch)
                        println("Error merging/deserializing state for key '$key' with transient properties: ${e.message}") // Use println
                        // Fallback: Try decoding the original saved state directly
                        try {
                            println("Falling back to deserializing saved state directly for key '$key'") // Use println
                            json.decodeFromJsonElement(serializer, savedJsonElement)
                        } catch (e2: Exception) {
                            println("Fallback deserialization failed for key '$key': ${e2.message}") // Use println
                            defaultValue // Final fallback to default value
                        }
                    }
                } else {
                    // If saved state or default state isn't a JsonObject, merging isn't reliable.
                    if (savedJsonElement !is JsonObject) {
                        println("Saved state for key '$key' is not a JSON object. Transient properties cannot be reliably reset from default.") // Use println
                    }
                    if (defaultJsonElement !is JsonObject) {
                        println("Default value for key '$key' does not serialize to a JSON object. Transient properties cannot be reliably reset.") // Use println
                    }
                    // Deserialize the original saved state directly (transient properties won't be reset)
                    println("Deserializing saved state directly for key '$key' without resetting transient properties.") // Use println
                    json.decodeFromJsonElement(serializer, savedJsonElement)
                }
                // --- End of Optimized Transient Property Restoration ---
            }
        } catch (e: Exception) {
            // Catch errors during initial deserialization (including fallback attempts)
            println("Error deserializing initial value for key '$key': ${e.message}") // Use println
            defaultValue // Fallback to default value if any deserialization fails
        }
    } else {
        // No saved state found, use the default value
        defaultValue
    }

    // Create the StateFlow with the determined initial value
    val stateFlow = MutableStateFlow(initialValue)

    // Observe the StateFlow and update SavedStateHandle on each change
    stateFlow.onEach { stateValue ->
        try {
            val jsonToSave = if (transientProperties.isEmpty()) {
                // No transient properties, serialize the entire state directly to string
                json.encodeToString(serializer, stateValue)
            } else {
                // Serialize but exclude transient properties if possible
                val jsonElement = json.encodeToJsonElement(serializer, stateValue)

                if (jsonElement is JsonObject) {
                    // Create a filtered JSON object without transient properties
                    val filteredJsonObject = buildJsonObject {
                        jsonElement.jsonObject.forEach { (propName, propValue) ->
                            if (propName !in transientProperties) {
                                put(propName, propValue)
                            }
                        }
                    }
                    // Store the filtered state as a string
                    json.encodeToString(JsonObject.serializer(), filteredJsonObject)
                } else {
                    // If not a JsonObject, we cannot filter transient properties reliably.
                    // Print a warning and serialize the entire state.
                    println("State for key '$key' does not serialize to a JSON object. Cannot filter transient properties during save. Saving entire state.") // Use println
                    json.encodeToString(serializer, stateValue)
                }
            }
            // Persist the JSON string to SavedStateHandle
            savedStateHandle.set(key, jsonToSave)

        } catch (e: Exception) {
            // Print errors during serialization
            println("Error serializing value for key '$key': ${e.message}") // Use println
        }
    }.launchIn(coroutineScope) // Launch the collector in the provided scope

    return stateFlow
}