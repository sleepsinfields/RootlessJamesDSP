package me.timschneeberger.rootlessjamesdsp.preference

import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import androidx.preference.Preference
import me.timschneeberger.rootlessjamesdsp.R

class DbbBinsPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceStyle
) : Preference(context, attrs, defStyleAttr), SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onAttached() {
        super.onAttached()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        refreshSummary()
    }

    override fun onDetached() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onDetached()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, changedKey: String) {
        if (changedKey == key) {
            refreshSummary()
            notifyChanged() // ✅ allowed here (protected in Preference, but we're in subclass)
        }
    }

    override fun onBindViewHolder(holder: androidx.preference.PreferenceViewHolder) {
        refreshSummary()
        super.onBindViewHolder(holder)
    }

    private fun refreshSummary() {
        val sp = preferenceManager.sharedPreferences ?: return
        val raw = sp.getString(key, "")?.trim().orEmpty()

        summary = if (raw.isEmpty()) {
            context.getString(R.string.dbb_freq_custom_summary)
        } else {
            raw
        }
    }
}