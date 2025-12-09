package me.timschneeberger.rootlessjamesdsp.preference

import android.content.SharedPreferences
import me.timschneeberger.rootlessjamesdsp.interop.JamesDspWrapper

object DBBPreferenceHelper {

    fun applyFromPreferences(
        prefs: SharedPreferences,
        dsp: JamesDspWrapper
    ) {
        val enabled = prefs.getBoolean("pref_dbb_enable", false)
        dsp.setBassBoostEnabled(enabled)
        if (!enabled) return

        val boostDb   = prefs.getInt("pref_dbb_boost_db", 6).toFloat()
        val widthPct  = prefs.getInt("pref_dbb_width", 50)
        val speedPct  = prefs.getInt("pref_dbb_speed", 50)
        val stabPct   = prefs.getInt("pref_dbb_stability", 50)
        val typeStr   = prefs.getString("pref_dbb_type", "1") ?: "1"
        val typeInt   = typeStr.toIntOrNull() ?: 1

        val widthNorm = (widthPct / 100f).coerceIn(0f, 1f)
        val speedNorm = (speedPct / 100f).coerceIn(0f, 1f)
        val stabNorm  = (stabPct / 100f).coerceIn(0f, 1f)

        dsp.setBassBoostAdvanced(
            boostDb,
            widthNorm,
            typeInt,
            speedNorm,
            stabNorm
        )
    }
}