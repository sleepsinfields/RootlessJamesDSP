package me.timschneeberger.rootlessjamesdsp.preference

import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import androidx.preference.Preference
import me.timschneeberger.rootlessjamesdsp.R

class DbbBinsPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs), SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onAttached() {
        super.onAttached()
        sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        updateSummary()
    }

    override fun onDetached() {
        sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onDetached()
    }

    // IMPORTANT: key is String? (nullable) to match the platform signature
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == this.key) {
            updateSummary()
            notifyChanged()
        }
    }

    private fun updateSummary() {
        val raw = sharedPreferences?.getString(key, "")?.trim().orEmpty()
        summary = if (raw.isEmpty()) {
            context.getString(R.string.dbb_freq_custom_summary)
        } else {
            raw
        }
    }
}