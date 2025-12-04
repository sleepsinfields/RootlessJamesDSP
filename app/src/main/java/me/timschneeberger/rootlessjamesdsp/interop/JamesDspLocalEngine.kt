// ---- Graphic EQ (mono) ----
override fun setGraphicEq(enable: Boolean, bands: String): Boolean {
    // Debug so we can see what's coming in
    Timber.e(
        "GeqDebug",
        "LocalEngine.setGraphicEq enable=$enable bands=${bands.take(80)}"
    )
    return JamesDspWrapper.setGraphicEq(handle, enable, bands)
}

// ---- Stereo Graphic EQ curves (M/L/R) ----
// master / left / right are the GraphicEQ: ... strings (nullable)
fun updateStereoGraphicEq(
    master: String?,
    left: String?,
    right: String?
): Boolean {
    Timber.e(
        "GeqDebug",
        "LocalEngine.updateStereoGraphicEq: " +
            "master=${master?.take(80)} " +
            "left=${left?.take(80)} right=${right?.take(80)}"
    )
    return JamesDspWrapper.updateStereoGraphicEq(handle, master, left, right)
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