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
    Log.e("DbbDebug", "DbbCustomBinsDialogFragment.onCreateDialog() showing")

    val ctx = requireContext()
    val prefsName = requireArguments().getString(ARG_PREFS_NAME)!!
    val key = requireArguments().getString(ARG_KEY)!!
    val targetFsKey = requireArguments().getString(ARG_TARGET_FS_KEY)!!

    val prefs = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
//
prefsRef = prefs
keyRef = key
//
    val existing = parseBins9(prefs.getString(key, "").orEmpty())

    val edits = ArrayList<TextInputEditText>(9)

    val container = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(8))
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    // Template buttons row (does NOT close)
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

    for (i in 0 until 9) {
        val til = TextInputLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            hint = "Bin ${i + 1} (Hz)"
        }

        val et = TextInputEditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(existing?.get(i)?.toString() ?: "")
        }

        til.addView(et)
        container.addView(til)
        edits.add(et)
    }

    fun writeEdits(arr: FloatArray) {
        for (i in 0 until 9) edits[i].setText(arr[i].toString())
    }

    fun readEdits(): FloatArray? {
        val out = FloatArray(9)
        for (i in 0 until 9) {
            val v = edits[i].text?.toString()?.trim()?.toFloatOrNull() ?: return null
            out[i] = if (v > 0f) v else 0f
        }
        return out
    }
//expose it to onStart()
readEditsFn = { readEdits() }

    btnDefault.setOnClickListener {
        writeEdits(defaultBins9())
    }

    btnLog.setOnClickListener {
        // Reads the *latest* TargetFs at click time (so it “tracks” when you press Fill Log)
        val latestTargetFs = readPrefFloatCompat(prefs, targetFsKey, 432f)
        val hi = latestTargetFs.coerceAtLeast(21f)
        writeEdits(logSpace9(low = 20f, high = hi))
    }

    val scroll = ScrollView(ctx).apply { addView(container) }

    // Bottom action row (ALWAYS visible)
    val actionRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(20), dp(8), dp(20), dp(16))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    val btnCancel = MaterialButton(ctx).apply {
        text = ctx.getString(R.string.dbb_bins_dialog_cancel)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(8)
        }
    }

    val btnSave = MaterialButton(ctx).apply {
        text = ctx.getString(R.string.dbb_bins_dialog_save)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    actionRow.addView(btnCancel)
    actionRow.addView(btnSave)

    val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        addView(actionRow)
    }

    val dialog = MaterialAlertDialogBuilder(ctx)
        .setTitle(ctx.getString(R.string.dbb_bins_dialog_title))
        .setMessage(ctx.getString(R.string.dbb_bins_dialog_help))
        .setView(root)
        .create()

    btnCancel.setOnClickListener { dialog.dismiss() }

    btnSave.setOnClickListener {
        val arr = readEdits()
        if (arr == null) {
            Toast.makeText(ctx, ctx.getString(R.string.dbb_bins_dialog_invalid), Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
//
prefs.edit().putString(key, arr.joinToString(";")).apply()

// Re-apply DSP immediately through the app’s existing pipeline
requireContext().sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
//
        dialog.dismiss()
    }
//
    dialog.setCanceledOnTouchOutside(false)
    isCancelable = true

    return dialog
}
//
override fun onStart() {
    super.onStart()

    val d = dialog as? androidx.appcompat.app.AlertDialog ?: return
    d.setCanceledOnTouchOutside(false)

    d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
        val prefs = prefsRef ?: return@setOnClickListener
        val key = keyRef ?: return@setOnClickListener
        val arr = readEditsFn?.invoke() ?: run {
            // optional: toast your "invalid" string here
            // Toast.makeText(requireContext(), getString(R.string.dbb_bins_dialog_invalid), Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }

        prefs.edit().putString(key, arr.joinToString(";")).apply()

        // ✅ refresh summary immediately
        parentFragmentManager.setFragmentResult("dbb_bins_updated", Bundle.EMPTY)

        // ✅ (optional) re-apply DSP immediately too
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