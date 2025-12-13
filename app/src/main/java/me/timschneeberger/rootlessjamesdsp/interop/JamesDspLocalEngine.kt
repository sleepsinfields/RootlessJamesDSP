package me.timschneeberger.rootlessjamesdsp.interop

import android.content.Context
import android.content.Intent
import me.timschneeberger.rootlessjamesdsp.interop.structure.EelVmVariable
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import timber.log.Timber
import java.util.Timer
import kotlin.concurrent.schedule
import me.timschneeberger.rootlessjamesdsp.fragment.GraphicEqualizerFragment
import android.util.Log



class JamesDspLocalEngine(
    context: Context,
    callbacks: JamesDspWrapper.JamesDspCallbacks? = null
) : JamesDspBaseEngine(context, callbacks) {

    var handle: JamesDspHandle = JamesDspWrapper.alloc(callbacks ?: DummyCallbacks())

    override var sampleRate: Float
        set(value) {
            super.sampleRate = value
            JamesDspWrapper.setSamplingRate(handle, value, false)
            context.sendLocalBroadcast(Intent(Constants.ACTION_SAMPLE_RATE_UPDATED))
        }
        get() = super.sampleRate

    override var enabled: Boolean = true

    init {
        if (BenchmarkManager.hasBenchmarksCached())
            BenchmarkManager.loadBenchmarksFromCache()
    }

    override fun close() {
        val oldHandle = handle
        handle = 0

        // Make sure ongoing async calls to native have enough time to finish
        Timer().schedule(100) {
            JamesDspWrapper.free(oldHandle)
            Timber.d("Handle $oldHandle has been freed")
        }
    }

    // Processing
    fun processInt16(input: ShortArray, output: ShortArray, offset: Int = -1, length: Int = -1) {
        if (!enabled || handle == 0L) {
            if (offset < 0 && length < 0) {
                input.copyInto(output)
            } else {
                input.copyInto(output, 0, offset, offset + length)
            }
        } else {
            JamesDspWrapper.processInt16(handle, input, output, offset, length)
        }
    }

    fun processInt32(input: IntArray, output: IntArray, offset: Int = -1, length: Int = -1) {
        if (!enabled || handle == 0L) {
            if (offset < 0 && length < 0) {
                input.copyInto(output)
            } else {
                input.copyInto(output, 0, offset, offset + length)
            }
        } else {
            JamesDspWrapper.processInt32(handle, input, output, offset, length)
        }
    }

    fun processFloat(input: FloatArray, output: FloatArray, offset: Int = -1, length: Int = -1) {
        if (!enabled || handle == 0L) {
            if (offset < 0 && length < 0) {
                input.copyInto(output)
            } else {
                input.copyInto(output, 0, offset, offset + length)
            }
        } else {
            JamesDspWrapper.processFloat(handle, input, output, offset, length)
        }
    }

    // Effect config
    override fun setOutputControl(threshold: Float, release: Float, postGain: Float): Boolean {
        return JamesDspWrapper.setLimiter(handle, threshold, release) &&
               JamesDspWrapper.setPostGain(handle, postGain)
    }

    override fun setReverb(enable: Boolean, preset: Int): Boolean {
        return JamesDspWrapper.setReverb(handle, enable, preset)
    }

    override fun setCrossfeed(enable: Boolean, mode: Int): Boolean {
        return JamesDspWrapper.setCrossfeed(handle, enable, mode, 0, 0)
    }

    override fun setCrossfeedCustom(enable: Boolean, fcut: Int, feed: Int): Boolean {
        return JamesDspWrapper.setCrossfeed(handle, enable, 99, fcut, feed)
    }

    override fun setBassBoost(enable: Boolean, maxGain: Float): Boolean {
    return JamesDspWrapper.setBassBoost(handle, enable, maxGain)
}

// 
/*
override fun setBassBoostAdvanced(
    enable: Boolean,
    maxGain: Float,
    targetFsHz: Double,
    detectMs: Double,
    gainMs: Double,
    resonance: Double,
    freqMode: Int
): Boolean {
    // Forward to JNI with minimal clamping; DSP will clamp internally too
    return JamesDspWrapper.setBassBoostAdvanced(
        handle,
        enable,
        maxGain,
        targetFsHz.toFloat(),
        detectMs.toFloat(),
        gainMs.toFloat(),
        resonance.toFloat(),
        freqMode
    )
}
*/
//

fun applyDbbTuning(
    targetFs: Float,
    detectMs: Float,
    gainMs: Float,
    resonance: Float,
    freqMode: Int
) {
    val safeTargetFs = targetFs.coerceIn(DBB_MIN_FS_HZ, DBB_MAX_FS_HZ)
    val safeDetectMs = detectMs.coerceIn(DBB_MIN_DETECT_MS, DBB_MAX_DETECT_MS)
    val safeGainMs   = gainMs.coerceIn(DBB_MIN_GAIN_MS, DBB_MAX_GAIN_MS)
    val safeRes      = resonance.coerceIn(DBB_MIN_RESONANCE, DBB_MAX_RESONANCE)
    val safeFreqMode = freqMode.coerceIn(DBB_MIN_FREQ_MODE, DBB_MAX_FREQ_MODE)

    try {
        Log.e(
            "DbbDebug",
            "applyDbbTuning (clamped): tf=$safeTargetFs Hz " +
                "dm=$safeDetectMs ms gm=$safeGainMs ms " +
                "res=$safeRes mode=$safeFreqMode handle=$handle"
        )

        JamesDspWrapper.setDbbTargetFs(handle, safeTargetFs)
        JamesDspWrapper.setDbbSmoothing(handle, safeDetectMs, safeGainMs)
        JamesDspWrapper.setDbbResonance(handle, safeRes)
        JamesDspWrapper.setDbbFreqMode(handle, safeFreqMode)

        // ✅ NEW: if "custom bins" mode, also send the 9 bin centers
        if (safeFreqMode == 2) {
            // TODO: replace with values loaded from prefs/UI
            val bins9 = floatArrayOf(
                20f, 35f, 55f, 80f, 110f, 160f, 250f, 400f, 650f
            )

            // Optional safety (recommended): ensure strictly positive
            for (i in 0 until 9) {
                if (!(bins9[i] > 0f)) bins9[i] = 0f
            }

            val ok = JamesDspWrapper.setDbbFreqCustom(handle, bins9)
            Log.e("DbbDebug", "setDbbFreqCustom(mode=2) ok=$ok bins=${bins9.joinToString(",")}")
        }
    } catch (t: Throwable) {
        Log.e("DbbDebug", "DBB JNI failed", t)
    }
}
//
override fun setStereoEnhancement(enable: Boolean, level: Float): Boolean {
        return JamesDspWrapper.setStereoEnhancement(handle, enable, level)
    }
//
// --------------------------------------------------------------------
// Core vacuum tube enable / drive (already mostly present, but unified)
// --------------------------------------------------------------------
override fun setVacuumTube(enable: Boolean, level: Double): Boolean {
    Log.e(
        "TubeDebug",
        "LocalEngine.setVacuumTube enable=$enable level(dB)=$level handle=$handle"
    )

    if (!JamesDspWrapper.isHandleValid(handle)) {
        Log.e("TubeDebug", "setVacuumTube: invalid handle=$handle, skipping native call")
        return false
    }

    return JamesDspWrapper.setVacuumTube(handle, enable, level)
}
// --------------------------------------------------------------------
// Shape mix: 0 = original allpass core, 1 = full triode curve
// --------------------------------------------------------------------

fun setVacuumTubeShape(mix: Double): Boolean {
    val clamped = mix.coerceIn(0.0, 1.0)

    if (!JamesDspWrapper.isHandleValid(handle)) {
        Log.e("TubeDebug", "setVacuumTubeShape: invalid handle=$handle, skipping")
        return false
    }

    Log.e("TubeDebug", "LocalEngine.setVacuumTubeShape mix=$clamped handle=$handle")
    return JamesDspWrapper.setTubeShape(handle, clamped)
}

fun setVacuumTubeHarmonics(evenGain: Double, oddGain: Double): Boolean {
    val e = evenGain.coerceAtLeast(0.0)
    val o = oddGain.coerceAtLeast(0.0)

    if (!JamesDspWrapper.isHandleValid(handle)) {
        Log.e("TubeDebug", "setVacuumTubeHarmonics: invalid handle=$handle, skipping")
        return false
    }

    Log.e("TubeDebug", "LocalEngine.setVacuumTubeHarmonics even=$e odd=$o handle=$handle")
    return JamesDspWrapper.setTubeHarmonics(handle, e, o)
}

fun setVacuumTubeHarmScale(scale: Double): Boolean {
    val s = scale.coerceIn(0.0, 1.0)

    if (!JamesDspWrapper.isHandleValid(handle)) {
        Log.e("TubeDebug", "setVacuumTubeHarmScale: invalid handle=$handle, skipping")
        return false
    }

    Log.e("TubeDebug", "LocalEngine.setVacuumTubeHarmScale scale=$s handle=$handle")
    return JamesDspWrapper.setTubeHarmScale(handle, s)
}

fun setVacuumTubeTriodeParams(drive: Double, bias: Double, scale: Double): Boolean {
    val d = drive.coerceIn(0.5, 4.0)
    val b = bias.coerceIn(-0.5, 0.5)
    val s = scale.coerceIn(0.1, 1.5)

    if (!JamesDspWrapper.isHandleValid(handle)) {
        Log.e("TubeDebug", "setVacuumTubeTriodeParams: invalid handle=$handle, skipping")
        return false
    }

    Log.e(
        "TubeDebug",
        "LocalEngine.setVacuumTubeTriodeParams drive=$d bias=$b scale=$s handle=$handle"
    )
    return JamesDspWrapper.setTubeTriodeParams(handle, d, b, s)
}

fun setVacuumTubeCoreTriode(enabled: Boolean): Boolean {
    if (!JamesDspWrapper.isHandleValid(handle)) {
        Log.e("TubeDebug", "setVacuumTubeCoreTriode: invalid handle=$handle, skipping")
        return false
    }

    Log.e("TubeDebug", "LocalEngine.setVacuumTubeCoreTriode enabled=$enabled handle=$handle")
    return JamesDspWrapper.setVacuumTubeCoreTriode(handle, enabled)
}

    override fun setMultiEqualizerInternal(
        enable: Boolean,
        filterType: Int,
        interpolationMode: Int,
        bands: DoubleArray
    ): Boolean {
        return JamesDspWrapper.setMultiEqualizer(handle, enable, filterType, interpolationMode, bands)
    }

    override fun setCompanderInternal(
        enable: Boolean,
        timeConstant: Float,
        granularity: Int,
        tfTransforms: Int,
        bands: DoubleArray
    ): Boolean {
        return JamesDspWrapper.setCompander(handle, enable, timeConstant, granularity, tfTransforms, bands)
    }

    override fun setVdcInternal(enable: Boolean, vdc: String): Boolean {
        return JamesDspWrapper.setVdc(handle, enable, vdc)
    }

    override fun setConvolverInternal(
        enable: Boolean,
        impulseResponse: FloatArray,
        irChannels: Int,
        irFrames: Int,
        irCrc: Int
    ): Boolean {
        return JamesDspWrapper.setConvolver(handle, enable, impulseResponse, irChannels, irFrames)
    }

    // ---- Graphic EQ (mono) ----
override fun setGraphicEqInternal(enable: Boolean, bands: String): Boolean {
    Timber.e(
        "GeqDebug",
        "LocalEngine.setGraphicEqInternal enable=$enable bands=${bands.take(80)}"
    )
    return JamesDspWrapper.setGraphicEq(handle, enable, bands)
}

// 
fun applyStereoGraphicEqFromPrefs(
    geqEnabled: Boolean,
    fallbackBands: String
): Boolean {
    // If disabled, turn stereo GEQ off in DSP and bail
    if (!geqEnabled) {
        Timber.e("GeqDebug", "applyStereoGraphicEqFromPrefs: disabled -> setStereoGraphicEq(false)")
        return JamesDspWrapper.setStereoGraphicEq(
            handle,
            false,
            "",
            "",
            ""
        )
    }

    val prefs = context.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)

    val masterRaw = prefs.getString(GraphicEqualizerFragment.PREF_GEQ_MASTER, null)
    val leftRaw   = prefs.getString(GraphicEqualizerFragment.PREF_GEQ_LEFT,   null)
    val rightRaw  = prefs.getString(GraphicEqualizerFragment.PREF_GEQ_RIGHT,  null)

    // MASTER must be non-null for the wrapper call
    val masterSafe = (masterRaw ?: fallbackBands)

    Timber.e(
        "GeqDebug",
        "applyStereoGraphicEqFromPrefs: enabled=$geqEnabled\n" +
            "  master=${masterSafe.take(64)}\n" +
            "  left=${leftRaw?.take(64)}\n" +
            "  right=${rightRaw?.take(64)}"
    )

    return JamesDspWrapper.updateStereoGraphicEq(
        self = handle,
        enable = true,          // you want it ON if we're here
        master = masterSafe,
        left = leftRaw,
        right = rightRaw
    )
}

// ---- Stereo ArbEq flags (global / M / L / R) ----
override fun setStereoArbEqFlagsInternal(
    global: Boolean,
    master: Boolean,
    left: Boolean,
    right: Boolean
) {
    Timber.e(
        "StereoEQ",
        "LocalEngine.setStereoArbEqFlagsInternal g=$global m=$master l=$left r=$right"
    )
    JamesDspWrapper.setArbEqStereoFlags(handle, global, master, left, right)
}

    private fun pushStereoGraphicEqToDsp(enable: Boolean, fallbackBands: String): Boolean {
    // If disabled, tell DSP to turn off the stereo GEQ.
    if (!enable) {
        return JamesDspWrapper.setStereoGraphicEq(
            handle,
            false,
            "",
            "",
            ""
        )
    }

    val prefs = context.getSharedPreferences(Constants.PREF_GEQ, Context.MODE_PRIVATE)

    // MASTER: try dedicated master key, fall back to the legacy single-curve string
    val master = prefs.getString(
        GraphicEqualizerFragment.PREF_GEQ_MASTER,
        null
    ) ?: fallbackBands

    // LEFT / RIGHT: may be null; updateStereoGraphicEq will fall back to master when they're null
    val left = prefs.getString(
        GraphicEqualizerFragment.PREF_GEQ_LEFT,
        null
    )

    val right = prefs.getString(
        GraphicEqualizerFragment.PREF_GEQ_RIGHT,
        null
    )

    return JamesDspWrapper.updateStereoGraphicEq(
        self = handle,
        enable = true,
        master = master,
        left = left,
        right = right
    )
}
//

//


/**
     * Normalize a side curve (LEFT/RIGHT) relative to the master.
     * - If side is null/blank, fall back to master.
     * - If it doesn't look like a GraphicEQ string, also fall back to master.
     */
    // not impilmented

    override fun setLiveprogInternal(enable: Boolean, name: String, script: String): Boolean {
        return JamesDspWrapper.setLiveprog(handle, enable, name, script)
    }

    // Feature support
    override fun supportsEelVmAccess(): Boolean { return true }
    override fun supportsCustomCrossfeed(): Boolean { return true }

    // EEL VM utilities
    override fun enumerateEelVariables(): ArrayList<EelVmVariable> {
        return JamesDspWrapper.enumerateEelVariables(handle)
    }

    override fun manipulateEelVariable(name: String, value: Float): Boolean {
        return JamesDspWrapper.manipulateEelVariable(handle, name, value)
    }

    override fun freezeLiveprogExecution(freeze: Boolean) {
        JamesDspWrapper.freezeLiveprogExecution(handle, freeze)
    }

    companion object {
        private const val PREF_GEQ_NODES_MASTER = "geq_nodes_master"
        private const val PREF_GEQ_NODES_LEFT   = "geq_nodes_left"
        private const val PREF_GEQ_NODES_RIGHT  = "geq_nodes_right"
//
// Pick ranges that are definitely safe for the C engine.
private const val DBB_MIN_FS_HZ      = 40f
private const val DBB_MAX_FS_HZ      = 4000f

private const val DBB_MIN_DETECT_MS  = 0.1f
private const val DBB_MAX_DETECT_MS  = 500f   // smoothing only, doesn't touch delay size

private const val DBB_MIN_GAIN_MS    = 0.5f
private const val DBB_MAX_GAIN_MS    = 20f    // << critical: keeps delay <= ~1024 samples

private const val DBB_MIN_RESONANCE  = 0.05f
private const val DBB_MAX_RESONANCE  = 4.0f

private const val DBB_MIN_FREQ_MODE  = 0
private const val DBB_MAX_FREQ_MODE  = 15    // 16-bin FFT in dbb.c
//
    }
}