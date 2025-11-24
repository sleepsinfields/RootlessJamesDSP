package me.timschneeberger.rootlessjamesdsp

object JdspNative {
    @JvmStatic
    external fun setStereoArbEqFlags(
        global: Boolean,
        master: Boolean,
        left: Boolean,
        right: Boolean
    )

    @JvmStatic
    external fun setStereoArbEqCurves(
        master: String,
        left: String,
        right: String
    )
}