package me.rerere.rikkahub.ui.hooks

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow


/** Preferences publish to Compose in the tap callback, before asynchronous disk/listener work. */
@Composable
fun rememberSharedPreferenceString(
    keyForString: String,
    defaultValue: String? = null,
): MutableState<String?> = rememberPreferenceState(
    keyForString, defaultValue,
    read = { prefs, key, default -> prefs.getString(key, default) },
    write = { key, value -> putString(key, value) },
)

@Composable
fun rememberSharedPreferenceBoolean(
    keyForBoolean: String,
    defaultValue: Boolean = false,
): MutableState<Boolean> = rememberPreferenceState(
    keyForBoolean, defaultValue,
    read = { prefs, key, default -> prefs.getBoolean(key, default) },
    write = { key, value -> putBoolean(key, value) },
)

@Composable
private fun <T> rememberPreferenceState(
    key: String,
    defaultValue: T,
    read: (SharedPreferences, String, T) -> T,
    write: SharedPreferences.Editor.(String, T) -> Unit,
): MutableState<T> {
    val context = LocalContext.current.applicationContext
    val prefs = remember(context) { context.getSharedPreferences("rikkahub.preferences", Context.MODE_PRIVATE) }
    val state = remember(prefs, key, defaultValue) { androidx.compose.runtime.mutableStateOf(read(prefs, key, defaultValue)) }
    androidx.compose.runtime.DisposableEffect(prefs, key, defaultValue) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == null || changed == key) state.value = read(prefs, key, defaultValue)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        // Cover a write between composition and registering the listener.
        state.value = read(prefs, key, defaultValue)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return remember(prefs, key, defaultValue) {
        object : MutableState<T> {
            override var value: T
                get() = state.value
                set(value) {
                    state.value = value
                    prefs.edit { write(key, value) }
                }
            override fun component1(): T = value
            override fun component2(): (T) -> Unit = { value = it }
        }
    }
}

fun Context.writeStringPreference(key: String, value: String?) {
    getSharedPreferences("rikkahub.preferences", Context.MODE_PRIVATE).edit {
        putString(key, value)
    }
}

fun Context.readStringPreference(key: String, defaultValue: String? = null): String? {
    return getSharedPreferences("rikkahub.preferences", Context.MODE_PRIVATE).getString(key, defaultValue)
}

fun Context.writeBooleanPreference(key: String, value: Boolean) {
    getSharedPreferences("rikkahub.preferences", Context.MODE_PRIVATE).edit {
        putBoolean(key, value)
    }
}

fun Context.readBooleanPreference(key: String, defaultValue: Boolean = false): Boolean {
    return getSharedPreferences("rikkahub.preferences", Context.MODE_PRIVATE).getBoolean(key, defaultValue)
}

fun SharedPreferences.getStringFlowForKey(keyForString: String, defaultValue: String? = null) =
    callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || keyForString == key) {
                trySend(getString(keyForString, defaultValue))
            }
        }
        registerOnSharedPreferenceChangeListener(listener)
        if (contains(keyForString)) {
            send(
                getString(
                    keyForString,
                    defaultValue
                )
            ) // if you want to emit an initial pre-existing value
        }
        awaitClose { unregisterOnSharedPreferenceChangeListener(listener) }
    }.buffer(Channel.CONFLATED) // State consumers only need the latest preference value.

fun SharedPreferences.getBooleanFlowForKey(keyForBoolean: String, defaultValue: Boolean = false) =
    callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || keyForBoolean == key) {
                trySend(getBoolean(keyForBoolean, defaultValue))
            }
        }
        registerOnSharedPreferenceChangeListener(listener)
        if (contains(keyForBoolean)) {
            send(
                getBoolean(
                    keyForBoolean,
                    defaultValue
                )
            ) // if you want to emit an initial pre-existing value
        }
        awaitClose { unregisterOnSharedPreferenceChangeListener(listener) }
    }.buffer(Channel.CONFLATED) // State consumers only need the latest preference value.
