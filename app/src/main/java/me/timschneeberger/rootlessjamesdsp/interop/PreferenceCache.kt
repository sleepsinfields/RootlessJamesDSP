package me.timschneeberger.rootlessjamesdsp.interop

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import me.timschneeberger.rootlessjamesdsp.flavor.CrashlyticsImpl
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import java.util.Locale
import kotlin.reflect.KClass

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
        } catch (_: Exception) {
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(@StringRes nameRes: Int, default: T, type: KClass<T>): T {
        if (selectedNamespace == null)
            throw IllegalStateException("No active namespace selected")

        val name = context.getString(nameRes)
        val current = uncachedGet(context, selectedNamespace!!, nameRes, default, type)
        val unchanged = cache.containsKey(name) && cache[name] == current
        if (!unchanged && !changedNamespaces.contains(selectedNamespace)) {
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

    // ---------------------------------------------------------------------
    // High-precision tube getter: "pretend the user just typed it"
    // ---------------------------------------------------------------------
    fun getDoubleTube(@StringRes nameRes: Int, default: Double): Double {
        if (selectedNamespace == null)
            throw IllegalStateException("No active namespace selected")

        val key = context.getString(nameRes)
        val prefs = getPreferences(context, selectedNamespace!!)

        return try {
            // Normal case: value stored as float by MaterialSeekbarPreference
            val storedFloat = prefs.getFloat(key, default.toFloat())

            // Emulate dialog -> string -> Double path at 17 decimals
            val asString = String.format(Locale.ROOT, "%.17f", storedFloat.toDouble())
            asString.toDouble()
        } catch (_: ClassCastException) {
            // Legacy case where it might have been stored as String
            val asString = prefs.getString(key, null)
            asString?.toDoubleOrNull() ?: default
        }
    }

    // Optional generic double writer (not critical for tube right now)
    fun putDouble(@StringRes keyRes: Int, value: Double) {
        if (selectedNamespace == null)
            throw IllegalStateException("No active namespace selected")

        val key = context.getString(keyRes)
        val prefs = getPreferences(context, selectedNamespace!!)

        val formatted = String.format(Locale.ROOT, "%.17f", value)
        prefs.edit().putString(key, formatted).apply()

        // Keep cache + change tracking consistent
        cache[key] = value
        if (!changedNamespaces.contains(selectedNamespace)) {
            selectedNamespace?.let { changedNamespaces.add(it) }
        }
    }

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