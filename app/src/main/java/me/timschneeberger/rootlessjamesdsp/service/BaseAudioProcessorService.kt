package me.timschneeberger.rootlessjamesdsp.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
// new for eq init when app starts
import me.timschneeberger.rootlessjamesdsp.JdspNative
import me.timschneeberger.rootlessjamesdsp.fragment.GraphicEqualizerFragment
import me.timschneeberger.rootlessjamesdsp.utils.Constants

abstract class BaseAudioProcessorService : Service() {
    private val binder: IBinder = LocalBinder()

    inner class LocalBinder : Binder() {
        val service: BaseAudioProcessorService
            get() = this@BaseAudioProcessorService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        activeServices++
        super.onCreate()
        //init eq on app load
        initGraphicEqFromPrefs(this)
    }


    override fun onDestroy() {
        activeServices--
        super.onDestroy()
    }

    companion object {
        var activeServices: Int = 0
            private set
    }

    private fun initGraphicEqFromPrefs(context: android.content.Context) {
    val prefs = context.getSharedPreferences(Constants.PREF_GEQ, android.content.Context.MODE_PRIVATE)

    // 1) Read stored curves, falling back to legacy single-string if needed
    val legacy = prefs.getString(
        context.getString(me.timschneeberger.rootlessjamesdsp.R.string.key_geq_nodes),
        me.timschneeberger.rootlessjamesdsp.utils.Constants.DEFAULT_GEQ
    ) ?: me.timschneeberger.rootlessjamesdsp.utils.Constants.DEFAULT_GEQ

    val masterRaw = prefs.getString(GraphicEqualizerFragment.PREF_GEQ_MASTER, null) ?: legacy
    val leftRaw   = prefs.getString(GraphicEqualizerFragment.PREF_GEQ_LEFT,   null) ?: masterRaw
    val rightRaw  = prefs.getString(GraphicEqualizerFragment.PREF_GEQ_RIGHT,  null) ?: masterRaw

    fun normalize(side: String, masterCurve: String, label: String): String {
        val trimmed = side.trim()
        if (trimmed.isEmpty()) return masterCurve
        if (!trimmed.contains(' ') || trimmed.length < 20) return masterCurve
        return side
    }

    val master = masterRaw
    val left   = normalize(leftRaw,  master, "LEFT")
    val right  = normalize(rightRaw, master, "RIGHT")

    // 2) Read stereo flags (same keys used in GraphicEqualizerFragment)
    val masterOn = prefs.getBoolean("flag_master", true)
    val leftOn   = prefs.getBoolean("flag_left",   false)
    val rightOn  = prefs.getBoolean("flag_right",  false)
    val global   = masterOn || leftOn || rightOn

    // 3) Push to native; ignore if native not ready yet
    try {
        JdspNative.setStereoArbEqCurves(
            master = master,
            left   = left,
            right  = right
        )
        JdspNative.setStereoArbEqFlags(
            global,
            masterOn,
            leftOn,
            rightOn
        )
    } catch (_: UnsatisfiedLinkError) {
        // Native layer not ready yet; safe to ignore on startup
    }
}
}
