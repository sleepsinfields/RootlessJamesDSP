object DBBPreferenceHelper {
    fun applyFromPreferences(
        prefs: SharedPreferences,
        engine: JamesDspBaseEngine // or JamesDspLocalEngine
    ) {
        val enabled = prefs.getBoolean("pref_dbb_enable", false)
        val boostDb = prefs.getInt("pref_dbb_boost_db", 6).toFloat()
        ...
        engine.setBassBoostAdvanced(
            enabled,
            boostDb,
            widthNorm,
            typeInt,
            speedNorm,
            stabNorm
        )
    }
}