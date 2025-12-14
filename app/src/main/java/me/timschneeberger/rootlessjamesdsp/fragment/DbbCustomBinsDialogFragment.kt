package me.timschneeberger.rootlessjamesdsp.fragment

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.fragment.app.DialogFragment
//
import androidx.appcompat.app.AlertDialog
import android.widget.Toast
//
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import me.timschneeberger.rootlessjamesdsp.R
import kotlin.math.exp
import kotlin.math.ln
import android.util.Log

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

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
//
Log.e("DbbDebug", "DbbCustomBinsDialogFragment.onCreateDialog() showing")
//
        val ctx = requireContext()
        val prefsName = requireArguments().getString(ARG_PREFS_NAME)!!
        val key = requireArguments().getString(ARG_KEY)!!
        val targetFsKey = requireArguments().getString(ARG_TARGET_FS_KEY)!!

        val prefs = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

        val existing = parseBins9(prefs.getString(key, "").orEmpty())
        val targetFs = readPrefFloatCompat(prefs, targetFsKey, 432f)

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
            text = ctx.getString(R.string.dbb_bins_dialog_fill_default) // add string
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }

        val btnLog = MaterialButton(ctx).apply {
            text = ctx.getString(R.string.dbb_bins_dialog_fill_log) // add string
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

        btnDefault.setOnClickListener {
            writeEdits(defaultBins9())
        }

        btnLog.setOnClickListener {
    val latestTargetFs = readPrefFloatCompat(prefs, targetFsKey, 432f)
    val hi = latestTargetFs.coerceAtLeast(21f)
    writeEdits(logSpace9(low = 20f, high = hi))
}

        val scroll = ScrollView(ctx).apply {
    isFillViewport = true
    addView(container)
}

val dialog = MaterialAlertDialogBuilder(ctx)
    .setTitle(ctx.getString(R.string.dbb_bins_dialog_title))
    .setMessage(ctx.getString(R.string.dbb_bins_dialog_help))
    .setView(scroll)
    .setNegativeButton(ctx.getString(R.string.dbb_bins_dialog_cancel), null)
    // We'll override OK in onStart so it doesn't auto-dismiss on invalid input
    .setPositiveButton(ctx.getString(R.string.dbb_bins_dialog_save), null)
    .create()

dialog.setCanceledOnTouchOutside(false)
isCancelable = true

// Important: override the positive click so invalid input doesn't close the dialog
dialog.setOnShowListener {
    val okBtn = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
    okBtn.setOnClickListener {
        val arr = readEdits()
        if (arr == null) {
            android.widget.Toast.makeText(
                ctx,
                ctx.getString(R.string.dbb_bins_dialog_invalid),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return@setOnClickListener
        }

        prefs.edit().putString(key, arr.joinToString(";")).apply()
        dialog.dismiss()
    }
}

return dialog
    }
//
override fun onStart() {
    super.onStart()

    val d = dialog as? androidx.appcompat.app.AlertDialog ?: return
    d.setCanceledOnTouchOutside(false)

    d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
        // call the same readEdits() you defined in onCreateDialog
        // (so we need to store it as a member — see note below)
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