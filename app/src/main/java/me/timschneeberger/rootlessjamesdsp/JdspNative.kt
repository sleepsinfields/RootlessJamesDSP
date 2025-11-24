package me.timschneeberger.rootlessjamesdsp

object JdspNative {

    // If necessary:
    // init {
    //     System.loadLibrary("jamesdsp") // adjust name if needed
    // }

    @JvmStatic
    external fun setStereoArbEqFlags(
        global: Boolean,
        master: Boolean,
        left: Boolean,
        right: Boolean
    )
}