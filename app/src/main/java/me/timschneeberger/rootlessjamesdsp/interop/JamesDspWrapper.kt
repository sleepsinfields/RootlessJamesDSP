package me.timschneeberger.rootlessjamesdsp.interop

import me.timschneeberger.rootlessjamesdsp.interop.structure.EelVmVariable
import me.timschneeberger.rootlessjamesdsp.model.ProcessorMessage

typealias JamesDspHandle = Long

object JamesDspWrapper {
    // Memory management
    external fun alloc(callbacks: JamesDspCallbacks): JamesDspHandle
    external fun free(self: JamesDspHandle)
    external fun isHandleValid(self: JamesDspHandle): Boolean

    // Benchmarking
    external fun getBenchmarkSize(): Int
    external fun runBenchmark(c0: DoubleArray, c1: DoubleArray)
    external fun loadBenchmark(c0: DoubleArray, c1: DoubleArray)

    // Processing (interleaved)
    external fun processInt16(self: JamesDspHandle, input: ShortArray, output: ShortArray, offset: Int = -1, length: Int = -1)
    external fun processInt8U24(self: JamesDspHandle, input: IntArray): IntArray
    external fun processInt24Packed(self: JamesDspHandle, input: BooleanArray): BooleanArray
    external fun processInt32(self: JamesDspHandle, input: IntArray, output: IntArray, offset: Int = -1, length: Int = -1)
    external fun processFloat(self: JamesDspHandle, input: FloatArray, output: FloatArray, offset: Int = -1, length: Int = -1)

    // Engine config
    external fun setSamplingRate(self: JamesDspHandle, sampleRate: Float, forceRefresh: Boolean)

    // Effect config
    external fun setLimiter(self: JamesDspHandle, threshold: Float, release: Float): Boolean
    external fun setPostGain(self: JamesDspHandle, postGain: Float): Boolean
    external fun setMultiEqualizer(self: JamesDspHandle, enable: Boolean, filterType: Int, interpolationMode: Int, bands: DoubleArray): Boolean
    external fun setVdc(self: JamesDspHandle, enable: Boolean, vdcContents: String): Boolean
    external fun setCompander(self: JamesDspHandle, enable: Boolean, timeConstant: Float, granularity: Int, tfResolution: Int, bands: DoubleArray): Boolean
    external fun setReverb(self: JamesDspHandle, enable: Boolean, preset: Int): Boolean
    
external fun setConvolver(
    self: JamesDspHandle,
    enable: Boolean,
    impulseResponse: FloatArray,
    irChannels: Int,
    irFrames: Int
): Boolean

external fun setGraphicEq(
    self: JamesDspHandle,
    enable: Boolean,
    graphicEq: String
): Boolean

external fun setStereoGraphicEq(
    self: JamesDspHandle,
    enable: Boolean,
    masterEq: String,
    leftEq: String,
    rightEq: String
): Boolean

external fun setArbEqStereoFlags(
    self: JamesDspHandle,
    globalEnable: Boolean,
    masterEnable: Boolean,
    leftEnable: Boolean,
    rightEnable: Boolean
)
fun updateStereoArbEq(
    self: JamesDspHandle,
    global: Boolean,
    master: Boolean,
    left: Boolean,
    right: Boolean
) {
    setArbEqStereoFlags(self, global, master, left, right)
}

fun updateStereoGraphicEq(
    self: JamesDspHandle,
    enable: Boolean,
    master: String,
    left: String?,
    right: String?
): Boolean {
    val leftSafe = left ?: master
    val rightSafe = right ?: master

    return setStereoGraphicEq(
        self,
        enable,
        master,
        leftSafe,
        rightSafe
    )
}

private fun normalizeSideCurve(raw: String?): String? {
    if (raw.isNullOrBlank()) return null

    val trimmed = raw.trim()

    if (trimmed.equals("GraphicEQ:", ignoreCase = true)) {
        return null
    }

    return trimmed
}
external fun setCrossfeed(
    self: JamesDspHandle,
    enable: Boolean,
    mode: Int,
    customFcut: Int,
    customFeed: Int
): Boolean

    external fun setBassBoost(self: JamesDspHandle, enable: Boolean, maxGain: Float): Boolean
    external fun setStereoEnhancement(self: JamesDspHandle, enable: Boolean, level: Float): Boolean
    external fun setVacuumTube(self: JamesDspHandle, enable: Boolean, level: Double): Boolean
//

// NEW: set tube shape (0f = square, 1f = cube)
external fun setVacuumTubeShape(
    self: JamesDspHandle,
    mix: Double
): Boolean

// Optional convenience wrapper
/* fun setTubeShape(self: JamesDspHandle, mix: Float): Boolean {
    val clamped = mix.coerceIn(0f, 1f)
    return setVacuumTubeShape(self, clamped)
} */
//
    external fun setLiveprog(self: JamesDspHandle, enable: Boolean, id: String, liveprogContent: String): Boolean

    // EEL VM utilities
    external fun enumerateEelVariables(self: JamesDspHandle): ArrayList<EelVmVariable>
    external fun manipulateEelVariable(self: JamesDspHandle, name: String, value: Float): Boolean
    external fun freezeLiveprogExecution(self: JamesDspHandle, freeze: Boolean)
    external fun eelErrorCodeToString(errorCode: Int): String

    // Callbacks
    interface JamesDspCallbacks
    {
        fun onLiveprogOutput(message: String)
        fun onLiveprogExec(id: String)
        fun onLiveprogResult(resultCode: Int, id: String, errorMessage: String?)
        fun onVdcParseError()
        fun onConvolverParseError(errorCode: ProcessorMessage.ConvolverErrorCode)
    }

    init
    {
        System.loadLibrary("jamesdsp-wrapper")
    }
}