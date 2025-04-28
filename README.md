# Reanimator 👻

[![Maven Central](https://img.shields.io/maven-central/v/eu.anifantakis/reanimator.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/eu.anifantakis/reanimator)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

[Also see a Demo App Using Reanimator](https://github.com/ioannisa/ReanimatorUIDemo)

**Effortless StateFlow persistence for Android and Kotlin Multiplatform ViewModels using SavedStateHandle.**

<img width="971" alt="image" src="https://github.com/user-attachments/assets/525ff7d2-9f67-43e9-8450-b7e5fc4ecf61" />

Reanimator simplifies managing ViewModel state persistence across process death (Android) or configuration changes by seamlessly integrating Kotlin's `StateFlow` with `SavedStateHandle`. It offers automatic state saving/restoring with selective persistence for transient properties, significantly reducing boilerplate code.

## Features ✨

* **StateFlow Persistence:** Automatically save and restore your `StateFlow<T>` state.
* **Selective Persistence:** Easily define "transient" properties within your state object that *won't* be persisted (e.g., loading flags, temporary errors).
* **Automatic Key Inference:** Uses the property name as the default key for `SavedStateHandle`, reducing configuration.
* **Kotlin Multiplatform (KMP):** Use Reanimator in your `commonMain` code for shared ViewModel logic across Android and other platforms supporting `SavedStateHandle` (via KMP ViewModel libraries).
* **Type-Safe:** Leverages `kotlinx.serialization` for robust state serialization (JSON by default).
* **Minimal Boilerplate:** Declare your persistent `StateFlow` in a single line.

## The Problem: Manual State Handling 🤯

Handling process death or configuration changes often involves manually interacting with `SavedStateHandle`:

* Defining unique keys.
* Getting/setting values manually.
* Observing state changes to trigger saving.
* Writing custom logic to filter out transient properties before saving.

This leads to repetitive, error-prone code that clutters your ViewModel.

## Reanimator: The Solution ✅

Reanimator provides the `getMutableStateFlow` extension function for `SavedStateHandle`.

```kotlin
// In your ViewModel (Android or KMP commonMain)
class MyViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {

    // Define which properties are transient
    private val transientProps = listOf("isLoading", "error")

    // Declare your state flow - Reanimator handles the rest!
    private val _uiState by savedStateHandle.getMutableStateFlow(
        defaultValue = MyUiState(),      // Initial/default state
        coroutineScope = viewModelScope, // Scope for saving changes
        transientProperties = transientProps // What NOT to save
        // key = "custom_state_key" // Optional: custom key
    )
    val uiState: StateFlow<MyUiState> = _uiState.asStateFlow()

    // ... rest of your ViewModel logic ...

    fun updateData(newData: List<String>) {
        // Just update the state - Reanimator saves persistent parts automatically
        _uiState.update { it.copy(data = newData, isLoading = false) }
    }
}

// Your state class (must be @Serializable)
@Serializable
data class MyUiState(
    // Persistent
    val data: List<String> = emptyList(),
    val selectedItem: String? = null,
    // Transient
    val isLoading: Boolean = false,
    val error: String? = null
)
```

With Reanimator, you simply define your state, mark it `@Serializable`, identify transient properties, and declare the `StateFlow`. Reanimator takes care of saving persistent data to `SavedStateHandle` whenever the state updates and restoring it correctly (while resetting transient fields) when the ViewModel is recreated.

## Setup ⚙️
Add the Reanimator dependency to your `build.gradle.kts` (or `build.gradle`) file.

**For Kotlin Multiplatform (commonMain):**

```Kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            // Navigation Library enables SavedStateHandle inside commonMain
            implementation(libs.jetbrains.compose.navigation)

            // Serialization library to handle complex data types for SavedStateHandle
            implementation(libs.kotlinx.serialization.json)

            implementation("eu.anifantakis:reanimator:1.0.4")
        }
    }
}
```

**For Android-only:**
```kotlin
dependencies {
    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Serialization library to handle complex data types for SavedStateHandle
    implementation(libs.kotlinx.serialization.json)

    implementation("eu.anifantakis:reanimator-android:1.0.4")
}
```

_(Remember to have `kotlinx.serialization` configured in your project.)_

### Usage 🚀
1. **Inject** `SavedStateHandle`: Ensure your ViewModel receives an instance of `SavedStateHandle`. This is standard practice in Android and supported by KMP ViewModel libraries.
2. **Define State**: Create a `@Serializable` data class for your UI state.
3. **Identify Transients**: Create a list of strings containing the names of properties in your state class that should not be persisted.
4. **Declare StateFlow**: Use the `by savedStateHandle.getMutableStateFlow(...)` property delegate.

### `getMutableStateFlow` Parameters

```kotlin
fun <reified T : Any> SavedStateHandle.getMutableStateFlow(
    defaultValue: T,                      // Required: The initial state and source for transient defaults.
    coroutineScope: CoroutineScope,       // Required: Scope to collect state changes for saving (e.g., viewModelScope).
    key: String? = null,                  // Optional: Custom key for SavedStateHandle. Defaults to the property name.
    transientProperties: List<String> = emptyList(), // Optional: List of property names to exclude from saving.
    json: Json = Json { ignoreUnknownKeys = true } // Optional: Custom kotlinx.serialization Json instance.
): PropertyDelegateProvider<Any, ReadOnlyProperty<Any, MutableStateFlow<T>>>
```

* **`defaultValue`**: The state instance used when no saved state exists, and crucially, the source for default values of `transientProperties` upon restoration.
* **`coroutineScope`**: The scope (usually `viewModelScope`) where the background collection runs to automatically save state changes.
* **`key`**: The string key used to store the state in `SavedStateHandle`. If null, the name of the delegated property (e.g., `_uiState`) is used.
* **`transientProperties`**: A list of property names (as strings) within your state class T that should be ignored during saving and reset to their `defaultValue` values during restoration.
* **`json`**: Allows providing a custom configured `kotlinx.serialization.json.Json` instance if needed (e.g., for specific encoding strategies). Defaults to a lenient instance that ignores unknown keys.

### How it Works (Briefly) 🧐
Reanimator observes the `MutableStateFlow` you create. On each update:

1. It serializes the current state object to a JSON element using `kotlinx.serialization`.
2. If `transientProperties` are defined and the state serializes to a JSON object, it removes the fields corresponding to those transient properties from the JSON.
3. It converts the (potentially filtered) JSON element back to a string.
4. It saves this JSON string into `SavedStateHandle` under the specified or inferred key.

When the ViewModel is recreated:

1. Reanimator attempts to retrieve the JSON string from `SavedStateHandle` using the key.
2. If found, it deserializes the JSON.
3. If `transientProperties` were defined, it intelligently merges the deserialized persistent data with the default values for transient properties taken from the defaultValue instance.
4. This merged state becomes the initial value of the `MutableStateFlow`.
5. If no saved state is found, `defaultValue` is used.


