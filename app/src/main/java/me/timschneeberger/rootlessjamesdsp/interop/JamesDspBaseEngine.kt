package me.timschneeberger.rootlessjamesdsp.interop

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.interop.structure.EelVmVariable
import me.timschneeberger.rootlessjamesdsp.model.ProcessorMessage
import me.timschneeberger.rootlessjamesdsp.preference.FileLibraryPreference
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.io.FileReader
import android.util.Log 
import me.timschneeberger.rootlessjamesdsp.fragment.GraphicEqualizerFragment
import me.timschneeberger.rootlessjamesdsp.interop.JamesDspLocalEngine

//



abstract class JamesDspBaseEngine(
    val context: Context,
    val callbacks: JamesDspWrapper.JamesDspCallbacks? = null
) : AutoCloseable {

    abstract var enabled: Boolean
    
    // Runtime toggle for advanced fractional shift mode
    var isAdvancedShiftEnabled: Boolean = false
        private set

    fun setAdvancedShiftEnabled(enabled: Boolean) {
        isAdvancedShiftEnabled = enabled
    }
    open var sampleRate: Float = 0.0f
        set(value) {
            field = value
            reportSampleRate(value)
        }

    private val syncScope = CoroutineScope(Dispatchers.IO)
    private val syncMutex = Mutex()
    protected val cache = PreferenceCache(context)

    companion object {
        // Scaling constants for advanced convolver parameters
        private const val ADV_DB_SCALE = 100000.0   // 0.00001 dB steps for thresholds
        private const val SHIFT_SCALE = 1000.0     // samples -> fixed-point
private const val TUBE_EPS = 1e-18  // or even 1e-7 if you want it stricter
    }
// 

private var lastTubeEnabled: Boolean = false
private var lastTubeDrive: Double = Double.NaN

private fun applyTubeIfChanged(enabled: Boolean, drive: Double) {
    val eps = 1e-6

    if (enabled == lastTubeEnabled && kotlin.math.abs(drive - lastTubeDrive) < eps) {
        Log.e(
            "TubeDebug",
            "applyTubeIfChanged: skipping DSP call (same as last within eps) " +
                    "enabled=$enabled drive=$drive last=$lastTubeDrive"
        )
        return
    }

    lastTubeEnabled = enabled
    lastTubeDrive = drive

    Log.e(
        "TubeDebug",
        "applyTubeIfChanged: calling setVacuumTube enabled=$enabled drive=$drive"
    )

    // Call into LocalEngine → JNI
    setVacuumTube(enabled, drive)
}
    
    // ---- Lifecycle ----

    override fun close() {
        Timber.d("Closing engine")
        reportSampleRate(0f)
        syncScope.cancel()
    }

    // ---- Preference sync ----

    open fun syncWithPreferences(forceUpdateNamespaces: Array<String>? = null) {
        syncScope.launch {
            syncWithPreferencesAsync(forceUpdateNamespaces)
        }
    }

    fun clearCache() {
        cache.clear()
    }

    private fun reportSampleRate(value: Float) {
        context.sendLocalBroadcast(Intent(Constants.ACTION_REPORT_SAMPLE_RATE).apply {
            putExtra(Constants.EXTRA_SAMPLE_RATE, value)
        })
    }

    private suspend fun syncWithPreferencesAsync(forceUpdateNamespaces: Array<String>? = null) {
        Timber.d(
            "Synchronizing with preferences... (forced: %s)",
            forceUpdateNamespaces?.joinToString(";") { it }
        )

        syncMutex.withLock {
            cache.select(Constants.PREF_OUTPUT)
            val outputPostGain = cache.get(R.string.key_output_postgain, 0f)
            val limiterThreshold = cache.get(R.string.key_limiter_threshold, -0.1f)
            val limiterRelease = cache.get(R.string.key_limiter_release, 60f)

            cache.select(Constants.PREF_COMPANDER)
            val compEnabled = cache.get(R.string.key_compander_enable, false)
            val compTimeConst = cache.get(R.string.key_compander_timeconstant, 0.22f)
            val compGranularity = cache.get(R.string.key_compander_granularity, 2f).toInt()
            val compTfTransforms = cache.get(R.string.key_compander_tftransforms, "0").toInt()
            val compResponse = cache.get(
                R.string.key_compander_response,
                "95.0;200.0;400.0;800.0;1600.0;3400.0;7500.0;0;0;0;0;0;0;0"
            )

            cache.select(Constants.PREF_BASS)
            val bassEnabled = cache.get(R.string.key_bass_enable, false)
            val bassMaxGain = cache.get(R.string.key_bass_max_gain, 5f)

            cache.select(Constants.PREF_EQ)
            val eqEnabled = cache.get(R.string.key_eq_enable, false)
            val eqFilterType = cache.get(R.string.key_eq_filter_type, "0").toInt()
            val eqInterpolationMode = cache.get(R.string.key_eq_interpolation, "0").toInt()
            val eqBands = cache.get(R.string.key_eq_bands, Constants.DEFAULT_EQ)

            cache.select(Constants.PREF_GEQ)
val geqEnabled = cache.get(R.string.key_geq_enable, false)
val geqBands = cache.get(R.string.key_geq_nodes, Constants.DEFAULT_GEQ_INTERNAL)

Log.e(
    "GeqDebug",
    "syncWithPreferences(engine=${System.identityHashCode(this)}): " +
        "geqEnabled=$geqEnabled bands=${geqBands.take(80)}..."
)

            cache.select(Constants.PREF_REVERB)
            val reverbEnabled = cache.get(R.string.key_reverb_enable, false)
            val reverbPreset = cache.get(R.string.key_reverb_preset, "0").toInt()

            cache.select(Constants.PREF_STEREOWIDE)
            val swEnabled = cache.get(R.string.key_stereowide_enable, false)
            val swMode = cache.get(R.string.key_stereowide_mode, 60f)

            cache.select(Constants.PREF_CROSSFEED)
            val crossfeedEnabled = cache.get(R.string.key_crossfeed_enable, false)
            val crossfeedMode = cache.get(R.string.key_crossfeed_mode, "0").toInt()

            cache.select(Constants.PREF_TUBE)
val tubeEnabled = cache.get(R.string.key_tube_enable, false)
val tubeDrive = PreferenceCache.getTubeDouble(
    context,
    floatKeyRes   = R.string.key_tube_drive,
    preciseKeyRes = R.string.key_tube_drive_precise,
    default       = 2.0
)

Log.e(
    "TubeDebug",
    "syncWithPreferences(engine=${System.identityHashCode(this)}): " +
        "enabled=$tubeEnabled drive=$tubeDrive"
)

applyTubeIfChanged(tubeEnabled, tubeDrive)
//

            cache.select(Constants.PREF_DDC)
            val ddcEnabled = cache.get(R.string.key_ddc_enable, false)
            val ddcFile = cache.get(R.string.key_ddc_file, "")

            cache.select(Constants.PREF_LIVEPROG)
            val liveProgEnabled = cache.get(R.string.key_liveprog_enable, false)
            val liveprogFile = cache.get(R.string.key_liveprog_file, "")

            cache.select(Constants.PREF_CONVOLVER)
            val convolverEnabled = cache.get(R.string.key_convolver_enable, false)
            val convolverFile = cache.get(R.string.key_convolver_file, "")
            val convolverAdvImp = cache.get(
                R.string.key_convolver_adv_imp,
                Constants.DEFAULT_CONVOLVER_ADVIMP
            )
            val convolverMode = cache.get(R.string.key_convolver_mode, "0").toInt()

            val targets = cache.changedNamespaces.toTypedArray() + (forceUpdateNamespaces ?: arrayOf())
            targets.forEach {
                Timber.i("Committing new changes in namespace '$it'")

                val result = when (it) {
                    Constants.PREF_OUTPUT -> setOutputControl(
                        limiterThreshold,
                        limiterRelease,
                        outputPostGain
                    )

                    Constants.PREF_COMPANDER -> setCompander(
                        compEnabled,
                        compTimeConst,
                        compGranularity,
                        compTfTransforms,
                        compResponse
                    )

                    Constants.PREF_BASS -> setBassBoost(bassEnabled, bassMaxGain)

                    Constants.PREF_EQ -> setMultiEqualizer(
                        eqEnabled,
                        eqFilterType,
                        eqInterpolationMode,
                        eqBands
                    )

                    
Constants.PREF_GEQ -> {
    val ok = if (this is JamesDspLocalEngine) {
        // Use the LocalEngine helper that reads M/L/R from prefs
        this.applyStereoGraphicEqFromPrefs(
            geqEnabled = geqEnabled,
            fallbackBands = geqBands
        )
    } else {
        // Non-local engines keep old mono behavior
        setGraphicEq(geqEnabled, geqBands)
    }
    ok
}
                    Constants.PREF_REVERB -> setReverb(reverbEnabled, reverbPreset)
                    Constants.PREF_STEREOWIDE -> setStereoEnhancement(swEnabled, swMode)
                    Constants.PREF_CROSSFEED -> setCrossfeed(crossfeedEnabled, crossfeedMode)
               
Constants.PREF_TUBE -> {
    // 1) Keep the old enable + gain behavior
    applyTubeIfChanged(tubeEnabled, tubeDrive)

    // 2) NEW: independent advanced tube controls, but only for local engine
    val local = this as? JamesDspLocalEngine
    if (local != null && tubeEnabled) {
        val tubePrefs = PreferenceCache.getPreferences(context, Constants.PREF_TUBE)

        fun readDouble(key: String, def: Double): Double {
            val raw = tubePrefs.getString(key, def.toString())
            return raw?.toDoubleOrNull() ?: def
        }

        fun clamp(v: Double, min: Double, max: Double) =
            v.coerceIn(min, max)

        val shapeMix = clamp(
            readDouble("tube_shape_mix", 0.30),
            0.0, 1.0
        )

        val harmScale = clamp(
            readDouble("tube_harm_scale", 0.20),
            0.0, 2.0
        )

        // even/odd can go a bit wild but cap them
        val evenGain = clamp(
            readDouble("tube_even_gain", 1.00),
            0.0, 4.0
        )
        val oddGain = clamp(
            readDouble("tube_odd_gain", 1.00),
            0.0, 4.0
        )

        val triodeDrive = clamp(
            readDouble("tube_triode_drive", 2.50),
            0.5, 5.0
        )
        val triodeBias = clamp(
            readDouble("tube_triode_bias", 0.00),
            -1.0, 1.0
        )
        val triodeScale = clamp(
            readDouble("tube_triode_scale", 0.50),
            0.1, 2.0
        )

        val coreTriodesOn = tubePrefs.getBoolean("tube_core_triodes", true)

        Log.e(
            "TubeDebug",
            "tubeShape=$shapeMix harmScale=$harmScale even=$evenGain odd=$oddGain " +
                "drive=$triodeDrive bias=$triodeBias scale=$triodeScale " +
                "coreTriodesOn=$coreTriodesOn"
        )

        local.setVacuumTubeShape(shapeMix)
        local.setVacuumTubeHarmScale(harmScale)
        local.setVacuumTubeHarmonics(evenGain, oddGain)
        local.setVacuumTubeTriodeParams(triodeDrive, triodeBias, triodeScale)
        local.setVacuumTubeCoreTriode(coreTriodesOn)
    }

    true
}
                    Constants.PREF_DDC -> setVdc(ddcEnabled, ddcFile)
                    Constants.PREF_LIVEPROG -> setLiveprog(liveProgEnabled, liveprogFile)
                    Constants.PREF_CONVOLVER -> setConvolver(
                        convolverEnabled,
                        convolverFile,
                        convolverMode,
                        convolverAdvImp
                    )

                    else -> true
                }

                if (!result) {
                    Timber.e("Failed to apply $it")
                }
            }

            cache.markChangesAsCommitted()
            Timber.i("Preferences synchronized")
        }
    }

    // ---- Public setters that parse strings and delegate to internal methods ----

    fun setMultiEqualizer(
        enable: Boolean,
        filterType: Int,
        interpolationMode: Int,
        bands: String
    ): Boolean {
        val doubleArray = DoubleArray(30)
        val array = bands.split(";")
        for ((i, str) in array.withIndex()) {
            val number = str.toDoubleOrNull()
            if (number == null) {
                Timber.e("setFirEqualizer: malformed EQ string")
                return false
            }
            doubleArray[i] = number
        }
        return setMultiEqualizerInternal(enable, filterType, interpolationMode, doubleArray)
    }

    fun setCompander(
        enable: Boolean,
        timeConstant: Float,
        granularity: Int,
        tfTransforms: Int,
        bands: String
    ): Boolean {
        val doubleArray = DoubleArray(14)
        val array = bands.split(";")
        for ((i, str) in array.withIndex()) {
            val number = str.toDoubleOrNull()
            if (number == null) {
                Timber.e("setCompander: malformed string")
                return false
            }
            doubleArray[i] = number
        }
        return setCompanderInternal(enable, timeConstant, granularity, tfTransforms, doubleArray)
    }

    fun setVdc(enable: Boolean, vdcPath: String): Boolean {
        val fullPath = FileLibraryPreference.createFullPathCompat(context, vdcPath)

        if (!File(fullPath).exists() || File(fullPath).isDirectory) {
            Timber.w("setVdc: file does not exist")
            setVdcInternal(false, "")
            return true // non-critical
        }

        return safeFileReader(fullPath)?.use {
            setVdcInternal(enable, it.readText())
        } ?: false
    }

    fun setConvolver(
        enable: Boolean,
        impulseResponsePath: String,
        optimizationMode: Int,
        waveEditStr: String
    ): Boolean {
        val path = FileLibraryPreference.createFullPathCompat(context, impulseResponsePath)

        // Handle disabled state before everything else
        if (!enable || !File(path).exists() || File(path).isDirectory) {
            setConvolverInternal(false, FloatArray(0), 0, 0, 0)
            return true
        }

      val advConv = waveEditStr.split(";")
val advSetting = IntArray(6)
advSetting.fill(0)

// Defaults for thresholds (same as before, just scaled)
advSetting[0] = (-80.0 * ADV_DB_SCALE).toInt()
advSetting[1] = (-100.0 * ADV_DB_SCALE).toInt()

try {
    if (advConv.size == 6) {
        for ((i, raw) in advConv.withIndex()) {
            val token = raw.trim()
            if (token.isEmpty()) continue

            when (i) {
                // 0,1: high precision dB thresholds
                0, 1 -> {
                    val db = token.toDoubleOrNull()
                    if (db == null) {
                        Timber.e("setConvolver: malformed dB value in AdvImp at index $i: '$token'")
                        callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.AdvParamsInvalid)
                        return false
                    }
                    advSetting[i] = (db * ADV_DB_SCALE).toInt()
                }

                // 2..5: shift in *samples*, allow decimals
                else -> {
                    val shiftSamples = token.toDoubleOrNull()
                    if (shiftSamples == null) {
                        Timber.e("setConvolver: malformed shift value in AdvImp at index $i: '$token'")
                        callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.AdvParamsInvalid)
                        return false
                    }
                    // store as fixed-point: samples * SHIFT_SCALE
                    advSetting[i] = (shiftSamples * SHIFT_SCALE).toInt()
                }
            }
        }
    } else {
        Timber.w("setConvolver: AdvImp setting has wrong size (${advConv.size})")
        callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.AdvParamsInvalid)
    }
} catch (ex: NumberFormatException) {
    Timber.e(ex, "setConvolver: NumberFormatException while parsing AdvImp setting")
    callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.AdvParamsInvalid)
    return false
}

        val info = IntArray(4)
        val imp = JdspImpResToolbox.ReadImpulseResponseToFloat(
            path,
            sampleRate.toInt(),
            info,
            optimizationMode,
            advSetting
        )

        if (imp == null) {
            Timber.e("setConvolver: Failed to read IR")
            setConvolverInternal(false, FloatArray(0), 0, 0, 0)
            callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.Corrupted)
            return false
        }

        // check frame count
        if (info[1] == 0) {
            Timber.e("setConvolver: IR has no frames")
            setConvolverInternal(false, FloatArray(0), 0, 0, 0)
            callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.NoFrames)
            return false
        }

        // check if advSetting was invalid
        if (info[3] == 0) {
            Timber.w("setConvolver: advSetting was invalid")
            callbacks?.onConvolverParseError(
                ProcessorMessage.ConvolverErrorCode.AdvParamsInvalid
            )
        }

        return setConvolverInternal(true, imp, info[0], info[1], info[2])
    }

fun setGraphicEq(enable: Boolean, bands: String): Boolean {
    // Sanity check
    if (!bands.contains("GraphicEQ:", ignoreCase = true)) {
        Timber.e("setGraphicEq: malformed string")
        setGraphicEqInternal(false, "")
        return false
    }

    return setGraphicEqInternal(enable, bands)
}

// new
fun setStereoArbEqFlags(
    global: Boolean,
    master: Boolean,
    left: Boolean,
    right: Boolean
) {
    // You can add whatever guards you want here (e.g. isConnected).
    setStereoArbEqFlagsInternal(global, master, left, right)
}
// end new

    fun setLiveprog(enable: Boolean, path: String): Boolean {
        val fullPath = FileLibraryPreference.createFullPathCompat(context, path)

        if (!File(fullPath).exists() || File(fullPath).isDirectory) {
            Timber.w("setLiveprog: file does not exist")
            return setLiveprogInternal(false, "", "")
        }

        return safeFileReader(fullPath)?.use {
            val name = File(fullPath).name
            setLiveprogInternal(enable, name, it.readText())
        } ?: false
    }

    private fun safeFileReader(path: String) =
        try {
            FileReader(path)
        } catch (ex: FileNotFoundException) {
            /* Exception may occur when old presets created with version <1.4.3 are swapped
               between root, rootless, debug, or release builds due to path name differences. */
            Timber.w(ex)
            null
        }

    // ---- Effect config (abstract) ----

    abstract fun setOutputControl(threshold: Float, release: Float, postGain: Float): Boolean
    abstract fun setReverb(enable: Boolean, preset: Int): Boolean
    abstract fun setCrossfeed(enable: Boolean, mode: Int): Boolean
    abstract fun setCrossfeedCustom(enable: Boolean, fcut: Int, feed: Int): Boolean
    abstract fun setBassBoost(enable: Boolean, maxGain: Float): Boolean
    abstract fun setStereoEnhancement(enable: Boolean, level: Float): Boolean
    abstract fun setVacuumTube(enable: Boolean, level: Double): Boolean

    protected abstract fun setMultiEqualizerInternal(
        enable: Boolean,
        filterType: Int,
        interpolationMode: Int,
        bands: DoubleArray
    ): Boolean

    protected abstract fun setCompanderInternal(
        enable: Boolean,
        timeConstant: Float,
        granularity: Int,
        tfTransforms: Int,
        bands: DoubleArray
    ): Boolean

    protected abstract fun setVdcInternal(enable: Boolean, vdc: String): Boolean

    protected abstract fun setConvolverInternal(
        enable: Boolean,
        impulseResponse: FloatArray,
        irChannels: Int,
        irFrames: Int,
        irCrc: Int
    ): Boolean

    protected abstract fun setGraphicEqInternal(enable: Boolean, bands: String): Boolean

// new
protected abstract fun setStereoArbEqFlagsInternal(
    global: Boolean,
    master: Boolean,
    left: Boolean,
    right: Boolean
)
// end new
    protected abstract fun setLiveprogInternal(
        enable: Boolean,
        name: String,
        script: String
    ): Boolean

    // ---- Feature support ----

    abstract fun supportsEelVmAccess(): Boolean
    abstract fun supportsCustomCrossfeed(): Boolean

    // ---- EEL VM utilities ----

    abstract fun enumerateEelVariables(): ArrayList<EelVmVariable>
    abstract fun manipulateEelVariable(name: String, value: Float): Boolean
    abstract fun freezeLiveprogExecution(freeze: Boolean)

    // ---- Dummy callbacks ----

    protected inner class DummyCallbacks : JamesDspWrapper.JamesDspCallbacks {
        override fun onLiveprogOutput(message: String) {}
        override fun onLiveprogExec(id: String) {}
        override fun onLiveprogResult(
            resultCode: Int,
            id: String,
            errorMessage: String?
        ) {
        }

        override fun onVdcParseError() {}
        override fun onConvolverParseError(errorCode: ProcessorMessage.ConvolverErrorCode) {}
    }
}