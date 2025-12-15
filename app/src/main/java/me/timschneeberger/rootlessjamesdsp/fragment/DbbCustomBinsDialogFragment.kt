package me.timschneeberger.rootlessjamesdsp.fragment
import android.util.Log
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import android.content.Intent
import kotlin.math.exp
import kotlin.math.ln

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

//


    private var prefsRef: android.content.SharedPreferences? = null
    private var keyRef: String? = null
    private var targetFsKeyRef: String? = null

    private val edits = ArrayList<TextInputEditText>(9)

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
    val ctx = requireContext()

    val prefsName = requireArguments().getString(ARG_PREFS_NAME)!!
    val key = requireArguments().getString(ARG_KEY)!!
    val targetFsKey = requireArguments().getString(ARG_TARGET_FS_KEY)!!

    val prefs = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    prefsRef = prefs
    keyRef = key
    targetFsKeyRef = targetFsKey

    // IMPORTANT: rebuild the view tree fresh every time
    edits.clear()

    val container = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(8))
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    // (Optional) template buttons row
    val btnRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
    }

    val btnDefault = MaterialButton(ctx).apply {
        text = ctx.getString(R.string.dbb_bins_dialog_fill_default)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(8)
        }
    }

    val btnLog = MaterialButton(ctx).apply {
        text = ctx.getString(R.string.dbb_bins_dialog_fill_log)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    btnRow.addView(btnDefault)
    btnRow.addView(btnLog)
    container.addView(btnRow)

    val existing = parseBins9(prefs.getString(key, "").orEmpty())

    for (i in 0 until 9) {
        val til = TextInputLayout(ctx).apply {
            hint = "Bin ${i + 1} (Hz)"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        val et = TextInputEditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(existing?.get(i)?.toString().orEmpty())
        }

        til.addView(et)
        container.addView(til)
        edits.add(et)
    }

    fun writeEdits(arr: FloatArray) {
        for (i in 0 until 9) edits[i].setText(arr[i].toString())
    }

    btnDefault.setOnClickListener { writeEdits(defaultBins9()) }
    btnLog.setOnClickListener {
        val hi = readPrefFloatCompat(prefs, targetFsKey, 432f).coerceAtLeast(21f)
        writeEdits(logSpace9(low = 20f, high = hi))
    }

    val scroll = ScrollView(ctx).apply {
        addView(container)
    }

    val dialog = MaterialAlertDialogBuilder(ctx)
        .setTitle(ctx.getString(R.string.dbb_bins_dialog_title))
        .setMessage(ctx.getString(R.string.dbb_bins_dialog_help))
        .setView(scroll)
        // THESE TWO LINES are what makes OK/Cancel exist:
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(android.R.string.ok, null) // override click in onStart()
        .create()

    dialog.setCanceledOnTouchOutside(false)
    isCancelable = true
    return dialog
}

override fun onStart() {
    super.onStart()

    val d = dialog as? AlertDialog ?: return
    d.setCanceledOnTouchOutside(false)

    d.window?.decorView?.post {
    val pos = d.getButton(AlertDialog.BUTTON_POSITIVE)
    val neg = d.getButton(AlertDialog.BUTTON_NEGATIVE)

    pos?.text = getString(R.string.dbb_bins_dialog_save)   // or android.R.string.ok
    neg?.text = getString(R.string.dbb_bins_dialog_cancel) // or android.R.string.cancel

    Log.e(
        "DbbDebug",
        "buttons(post): pos=${pos?.width}x${pos?.height} text='${pos?.text}' " +
            "neg=${neg?.width}x${neg?.height} text='${neg?.text}'"
    )

    pos?.setOnClickListener {
        val prefs = prefsRef ?: return@setOnClickListener
        val key = keyRef ?: return@setOnClickListener

        val arr = readEdits() ?: run {
            Toast.makeText(requireContext(), getString(R.string.dbb_bins_dialog_invalid), Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }

        prefs.edit().putString(key, arr.joinToString(";")).apply()
        parentFragmentManager.setFragmentResult("dbb_bins_updated", Bundle.EMPTY)
        requireContext().sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
        dismiss()
    }

    neg?.setOnClickListener { dismiss() }
}
}
//
    private fun readEdits(): FloatArray? {
        if (edits.size != 9) return null
        val out = FloatArray(9)
        for (i in 0 until 9) {
            val v = edits[i].text?.toString()?.trim()?.toFloatOrNull() ?: return null
            out[i] = if (v > 0f) v else 0f
        }
        return out
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

    private fun readPrefFloatCompat(prefs: SharedPreferences, key: String, def: Float): Float {
        if (key.isBlank()) return def
        try { return prefs.getFloat(key, def) } catch (_: ClassCastException) {}
        val s: String? = try { prefs.getString(key, null) } catch (_: ClassCastException) { null }
        return s?.toFloatOrNull() ?: def
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}