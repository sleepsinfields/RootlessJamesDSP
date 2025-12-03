package me.timschneeberger.rootlessjamesdsp.interop

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import me.timschneeberger.rootlessjamesdsp.flavor.CrashlyticsImpl
import kotlin.reflect.KClass
import java.util.Locale

class PreferenceCache(val context: Context) {
    val changedNamespaces = ArrayList<String>()
    val cache: HashMap<String, Any> = hashMapOf()
    var selectedNamespace: String? = null

    fun select(namespace: String) {
        selectedNamespace = namespace
    }

    fun clear() {
        try {
            cache.clear()
        }
        catch (_: Exception) {}
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(@StringRes nameRes: Int, default: T, type: KClass<T>): T {
        if(selectedNamespace == null)
            throw IllegalStateException("No active namespace selected")

        val name = context.getString(nameRes)
        val current = uncachedGet(context, selectedNamespace!!, nameRes, default, type)
        val unchanged = cache.containsKey(name) && cache[name] == current
        if(!unchanged && !changedNamespaces.contains(selectedNamespace)) {
            selectedNamespace?.let {
                changedNamespaces.add(it)
            }
        }

        CrashlyticsImpl.setCustomKey("dsp_$name", current.toString())
        cache[name] = current as Any
        return current
    }

    inline fun <reified T : Any> get(@StringRes nameRes: Int, default: T) =
        get(nameRes, default, T::class)

    fun markChangesAsCommitted() {
        changedNamespaces.clear()
    }
//
fun getDoubleTube(@StringRes nameRes: Int, default: Double): Double {
    if (selectedNamespace == null)
        throw IllegalStateException("No active namespace selected")

    val key = context.getString(nameRes)
    val prefs = getPreferences(context, selectedNamespace!!)

    return try {
        // Normal case: value stored as float by MaterialSeekbarPreference
        val storedFloat = prefs.getFloat(key, default.toFloat())

        // Pretend the user *saw* this value in the dialog and hit OK:
        // 1) format it like the dialog would
        // 2) parse that string back to a Double
        //
        // Use 17 decimals to match your “sounds best at 17 places” tests.
        val asString = String.format(Locale.ROOT, "%.17f", storedFloat.toDouble())
        asString.toDouble()
    } catch (e: ClassCastException) {
        // Legacy / experimental cases where we might have stored a String
        val asString = prefs.getString(key, null)
        asString?.toDoubleOrNull() ?: default
    }
}
// 
fun putDouble(@StringRes keyRes: Int, value: Double) {
    val key = context.getString(keyRes)
    val prefs = getPreferences(context, selectedNamespace!!)
    prefs.edit().putString(key, value.toString()).apply()
}
// 
    companion object {
        @Suppress("DEPRECATION")
        fun getPreferences(
            context: Context,
            namespace: String,
        ): SharedPreferences {
            return context.getSharedPreferences(namespace, Context.MODE_MULTI_PROCESS)
        }

        @Suppress("UNCHECKED_CAST")
        fun <T : Any> uncachedGet(
            context: Context,
            namespace: String,
            @StringRes nameRes: Int,
            default: T,
            type: KClass<T>
        ): T {
            val name = context.getString(nameRes)
            val prefs = getPreferences(context, namespace)
            
val current: T = when (type) {
    Boolean::class -> prefs.getBoolean(name, default as Boolean) as T

    String::class -> prefs.getString(name, default as String) as T

    Int::class -> prefs.getInt(name, default as Int) as T

    Float::class -> prefs.getFloat(name, default as Float) as T

    Double::class -> {
        // Try string-based storage first (full precision)
        try {
            val raw = prefs.getString(name, null)
            if (!raw.isNullOrBlank()) {
                raw.toDoubleOrNull()?.let { return it as T }
            }
        } catch (_: ClassCastException) {
            // fall through to legacy float path
        }

        // Legacy fallback: value may be stored as Float
        val def = default as Double
        val legacyFloat = prefs.getFloat(name, def.toFloat())
        legacyFloat.toDouble() as T
    }

    else -> throw IllegalArgumentException("Unknown type")
}
            return current
        }

        inline fun <reified T : Any> uncachedGet(
            context: Context,
            namespace: String,
            @StringRes nameRes: Int,
            default: T
        ) = uncachedGet(context, namespace, nameRes, default, T::class)
    }
}