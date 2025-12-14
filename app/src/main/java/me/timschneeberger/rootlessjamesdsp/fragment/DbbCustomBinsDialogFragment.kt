package me.timschneeberger.rootlessjamesdsp.fragment
//
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
//
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.fragment.app.DialogFragment
import androidx.appcompat.app.AlertDialog
import android.widget.Toast
import android.view.View
import android.util.Log
import android.content.Intent
import kotlin.math.exp
import kotlin.math.ln
//
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout



class DbbCustomBinsDialogFragment : DialogFragment() {

    companion object {
        private const val ARG_PREFS_NAME = "prefs_name"
        private const val ARG_KEY = "key"
        private const val ARG_TARGET_FS_KEY = "target_fs_key"

        fun newInstance(prefsName: String, key: String, targetFsKey: String) =
            DbbCustomBinsDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PREFS_NAME, prefsName)
                    putString(ARG_KEY, key)
                    putString(ARG_TARGET_FS_KEY, targetFsKey)
                }
            }
    }
//
private var prefsRef: android.content.SharedPreferences? = null
private var keyRef: String? = null
private var readEditsFn: (() -> FloatArray?)? = null
//
//
 override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
    val ctx = requireContext()

    val prefsName = requireArguments().getString(ARG_PREFS_NAME)!!
    val key = requireArguments().getString(ARG_KEY)!!
    val targetFsKey = requireArguments().getString(ARG_TARGET_FS_KEY)!!
    val prefs = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    // IMPORTANT: create NEW views here (no class-level ScrollView/container fields)
    val container = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(8))
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    // ... add template button row + 9 TextInputLayouts into `container` ...

    val scroll = ScrollView(ctx).apply {
        addView(container) // attach container to scroll ONCE
    }

    // Keep refs for onStart save logic (prefs/key + readEditsFn are fine)
    prefsRef = prefs
    keyRef = key
    readEditsFn = { /* return FloatArray? from your edits list */ null }

    return MaterialAlertDialogBuilder(ctx)
        .setTitle(ctx.getString(R.string.dbb_bins_dialog_title))
        .setMessage(ctx.getString(R.string.dbb_bins_dialog_help))
        .setView(scroll) // attach scroll to dialog ONCE
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(android.R.string.ok, null) // we override click in onStart
        .create()
        .apply { setCanceledOnTouchOutside(false) }
}
//
override fun onStart() {
    super.onStart()

    val d = dialog as? AlertDialog ?: return
    d.setCanceledOnTouchOutside(false)

    d.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
        val prefs = prefsRef ?: return@setOnClickListener
        val key = keyRef ?: return@setOnClickListener
        val arr = readEditsFn?.invoke() ?: return@setOnClickListener

        prefs.edit().putString(key, arr.joinToString(";")).apply()

        parentFragmentManager.setFragmentResult("dbb_bins_updated", Bundle.EMPTY)

        requireContext().sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))

        dismiss()
    }
}
//
    private fun defaultBins9(): FloatArray =
        floatArrayOf(20f, 35f, 55f, 80f, 110f, 160f, 250f, 400f, 650f)

    private fun logSpace9(low: Float, high: Float): FloatArray {
        val out = FloatArray(9)
        val l0 = ln(low.toDouble())
        val l1 = ln(high.toDouble())
        for (i in 0 until 9) {
            val t = i.toDouble() / 8.0
            out[i] = exp(l0 + (l1 - l0) * t).toFloat()
        }
        return out
    }

    private fun parseBins9(raw: String): FloatArray? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val parts = s.split(';', ',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size != 9) return null
        val out = FloatArray(9)
        for (i in 0 until 9) {
            val v = parts[i].toFloatOrNull() ?: return null
            out[i] = if (v > 0f) v else 0f
        }
        return out
    }

    private fun readPrefFloatCompat(
        prefs: android.content.SharedPreferences,
        key: String,
        def: Float
    ): Float {
        if (key.isBlank()) return def
        try { return prefs.getFloat(key, def) } catch (_: ClassCastException) { }
        val s: String? = try { prefs.getString(key, null) } catch (_: ClassCastException) { null }
        return s?.toFloatOrNull() ?: def
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}