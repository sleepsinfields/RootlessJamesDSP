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

    override fun setStereoEnhancement(enable: Boolean, level: Float): Boolean {
        return JamesDspWrapper.setStereoEnhancement(handle, enable, level)
    }

   override fun setVacuumTube(enable: Boolean, level: Double): Boolean {
    Log.e(
        "TubeDebug",
        "LocalEngine.setVacuumTube enable=$enable levelDouble=$level"
    )
    return JamesDspWrapper.setVacuumTube(handle, enable, level)
}
//

// Optional helper to adjust shape from Kotlin:
fun setVacuumTubeShape(mix: Float): Boolean {
    Log.e("TubeDebug", "LocalEngine.setVacuumTubeShape mix=$mix")
    return JamesDspWrapper.setVacuumTubeShape(handle, mix)
}
//

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
    }
}