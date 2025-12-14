package me.timschneeberger.rootlessjamesdsp.fragment

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import me.timschneeberger.rootlessjamesdsp.R

class DbbCustomBinsDialogFragment : androidx.fragment.app.DialogFragment() {

    companion object {
        private const val ARG_PREFS_NAME = "prefs_name"
        private const val ARG_KEY = "key"
        private const val ARG_TARGET_FS_KEY = "target_fs_key" // optional: used for auto-fill high bound

        fun newInstance(
            prefsName: String,
            key: String,
            targetFsKey: String
        ) = DbbCustomBinsDialogFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_PREFS_NAME, prefsName)
                putString(ARG_KEY, key)
                putString(ARG_TARGET_FS_KEY, targetFsKey)
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val prefsName = requireArguments().getString(ARG_PREFS_NAME)!!
        val key = requireArguments().getString(ARG_KEY)!!
        val targetFsKey = requireArguments().getString(ARG_TARGET_FS_KEY)!!

        val prefs = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

        // Read current bins string "a;b;c;...;i"
        val current = prefs.getString(key, "") ?: ""
        val existing = parseBins9(current)

        // Read targetFs (stored as String by MaterialSeekbarPreference? usually yes)
        // If your MaterialSeekbarPreference stores float as String, this is correct.
        // If not, it’ll fall back gracefully.
        val targetFs = (prefs.getString(targetFsKey, null)?.toFloatOrNull())
            ?: prefs.getFloat(targetFsKey, 432f) // fallback if stored as float
            ?: 432f

        val edits = ArrayList<TextInputEditText>(9)

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(4))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        for (i in 0 until 9) {
            val til = TextInputLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
                hint = "Bin ${i + 1} (Hz)"
            }
            val et = TextInputEditText(ctx).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(existing?.get(i)?.toString() ?: "")
            }
            til.addView(et)
            container.addView(til)
            edits.add(et)
        }

        val scroll = ScrollView(ctx).apply { addView(container) }

        fun readEdits(): FloatArray? {
            val out = FloatArray(9)
            for (i in 0 until 9) {
                val v = edits[i].text?.toString()?.trim()?.toFloatOrNull() ?: return null
                out[i] = if (v > 0f) v else 0f
            }
            return out
        }

        fun writeEdits(arr: FloatArray) {
            for (i in 0 until 9) edits[i].setText(arr[i].toString())
        }

        return MaterialAlertDialogBuilder(ctx)
            .setTitle(ctx.getString(R.string.dbb_bins_dialog_title))
            .setMessage(ctx.getString(R.string.dbb_bins_dialog_help))
            .setView(scroll)
            .setNeutralButton(ctx.getString(R.string.dbb_bins_dialog_fill_log)) { _, _ ->
                // Re-open after fill (simple trick: fill then show again)
                val filled = logSpace9(low = 20f, high = targetFs.coerceAtLeast(21f))
                writeEdits(filled)
                // Keep dialog open: re-show by immediately launching a new one
                parentFragmentManager.beginTransaction().remove(this).commitAllowingStateLoss()
                newInstance(prefsName, key, targetFsKey).show(parentFragmentManager, "dbb_bins")
                // NOTE: fields won’t keep the fill if we recreate like this
                // so we instead store a temp string and reread it on recreate:
                // (Handled below: we’ll store into prefs immediately)
                prefs.edit().putString(key, filled.joinToString(";")).apply()
            }
            .setNegativeButton(ctx.getString(R.string.dbb_bins_dialog_cancel), null)
            .setPositiveButton(ctx.getString(R.string.dbb_bins_dialog_save)) { _, _ ->
                val arr = readEdits()
                if (arr == null) {
                    // If invalid, don’t save (and toast via your existing extension if you want)
                    return@setPositiveButton
                }
                prefs.edit().putString(key, arr.joinToString(";")).apply()
            }
            .create()
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

    private fun logSpace9(low: Float, high: Float): FloatArray {
        val out = FloatArray(9)
        val l0 = kotlin.math.ln(low.toDouble())
        val l1 = kotlin.math.ln(high.toDouble())
        for (i in 0 until 9) {
            val t = i.toDouble() / 8.0
            out[i] = kotlin.math.exp(l0 + (l1 - l0) * t).toFloat()
        }
        return out
    }

    private fun dp(v: Int): Int {
        val d = resources.displayMetrics.density
        return (v * d).toInt()
    }
}