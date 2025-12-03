package me.timschneeberger.rootlessjamesdsp.flavor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.interop.JamesDspLocalEngine
import me.timschneeberger.rootlessjamesdsp.interop.ProcessorMessageHandler
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

abstract class JamesDspBasePlugin : KoinComponent, AutoCloseable {
    protected val context: Context by inject()

    // Engine
    protected val engine = JamesDspLocalEngine(context, ProcessorMessageHandler())

    // Preferences
    protected val preferences: Preferences.App by inject()
    private val onPreferenceChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when(key) {
            context.getString(R.string.key_powered_on) -> {
                engine.enabled = preferences.get<Boolean>(R.string.key_powered_on)
            }
        }
    }

    // General purpose broadcast receiver
    private val broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Constants.ACTION_SAMPLE_RATE_UPDATED -> {
                android.util.Log.e("TubeDebug", "BasePlugin: ACTION_SAMPLE_RATE_UPDATED -> syncWithPreferences(CONVOLVER)")
                engine.syncWithPreferences(arrayOf(Constants.PREF_CONVOLVER))
            }
            Constants.ACTION_PREFERENCES_UPDATED -> {
                android.util.Log.e("TubeDebug", "BasePlugin: ACTION_PREFERENCES_UPDATED -> syncWithPreferences(ALL)")
                engine.syncWithPreferences()
            }
            Constants.ACTION_SERVICE_RELOAD_LIVEPROG -> {
                android.util.Log.e("TubeDebug", "BasePlugin: ACTION_SERVICE_RELOAD_LIVEPROG -> syncWithPreferences(LIVEPROG)")
                engine.syncWithPreferences(arrayOf(Constants.PREF_LIVEPROG))
            }
        }
    }
}
    init {
        preferences.registerOnSharedPreferenceChangeListener(onPreferenceChanged)
        // Setup general-purpose broadcast receiver
        context.registerLocalReceiver(broadcastReceiver, IntentFilter().apply {
            addAction(Constants.ACTION_PREFERENCES_UPDATED)
            addAction(Constants.ACTION_SAMPLE_RATE_UPDATED)
            addAction(Constants.ACTION_SERVICE_RELOAD_LIVEPROG)
            addAction(Constants.ACTION_SERVICE_HARD_REBOOT_CORE)
            addAction(Constants.ACTION_SERVICE_SOFT_REBOOT_CORE)
        })
        context.registerReceiver(broadcastReceiver, IntentFilter())
        engine.enabled = preferences.get<Boolean>(R.string.key_powered_on)
        init {
    ...
    android.util.Log.e("TubeDebug", "BasePlugin: init() -> syncWithPreferences()")
    engine.syncWithPreferences()
}

    override fun close() {
        context.unregisterLocalReceiver(broadcastReceiver)
        preferences.unregisterOnSharedPreferenceChangeListener(onPreferenceChanged)
        engine.close()
    }
}