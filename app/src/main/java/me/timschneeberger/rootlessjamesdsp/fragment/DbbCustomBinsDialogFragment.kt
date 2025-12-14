package me.timschneeberger.rootlessjamesdsp.fragment

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import me.timschneeberger.rootlessjamesdsp.R

class DbbCustomBinsDialogFragment : DialogFragment() {

    companion object {
        private const val ARG_PREFS_NAME = "prefs_name"
        private const val ARG_KEY = "key"
        private const val ARG_TARGET_FS_KEY = "target_fs_key"

        fun newInstance(
            prefsName: String,
            key: String,
            targetFsKey: String
        ): DbbCustomBinsDialogFragment {
            return DbbCustomBinsDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PREFS_NAME, prefsName)
                    putString(ARG_KEY, key)
                    putString(ARG_TARGET_FS_KEY, targetFsKey)
                }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val prefsName = requireArguments().getString(ARG_PREFS_NAME)!!
        val key = requireArguments().getString(ARG_KEY)!!
        val targetFsKey = requireArguments().getString(ARG_TARGET_FS_KEY)!!

        val prefs = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

        // Current bins string: "a;b;c;...;i"
        val current = prefs.getString(key, "").orEmpty()
        val existing = parseBins9(current)

        // targetFs: try String first (many prefs store as string), fallback to float
        val targetFs: Float = run {
            val asString = prefs.getString(targetFsKey, null)?.toFloatOrNull()
            if (asString != null) asString else prefs.getFloat(targetFsKey, 432f)
        }

        val edits = ArrayList<TextInputEditText>(9)

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(4))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // --- Template buttons row (does NOT close dialog) ---
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

        val btnDefault = MaterialButton(ctx).apply {
            text = ctx.getString(R.string.dbb_bins_dialog_fill_default) // add string if you want
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }

        val btnLog = MaterialButton(ctx).apply {
            text = ctx.getString(R.string.dbb_bins_dialog_fill_log) // you already have this string
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        btnRow.addView(btnDefault)
        btnRow.addView(btnLog)
        container.addView(btnRow)

        // 9 input fields
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

        val scroll = ScrollView(ctx).apply { addView(container) }

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

        fun defaultBins9(): FloatArray {
            // Your “default template”
            return floatArrayOf(20f, 35f, 55f, 80f, 110f, 160f, 250f, 400f, 650f)
        }

        btnDefault.setOnClickListener {
            writeEdits(defaultBins9())
        }

        btnLog.setOnClickListener {
            val high = targetFs.coerceAtLeast(21f)
            writeEdits(logSpace9(20f, high))
        }

        return MaterialAlertDialogBuilder(ctx)
            .setTitle(ctx.getString(R.string.dbb_bins_dialog_title))
            .setMessage(ctx.getString(R.string.dbb_bins_dialog_help))
            .setView(scroll)
            .setNegativeButton(ctx.getString(R.string.dbb_bins_dialog_cancel), null)
            .setPositiveButton(ctx.getString(R.string.dbb_bins_dialog_save)) { _, _ ->
                val arr = readEdits() ?: return@setPositiveButton
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
        return (v * d + 0.5f).toInt()
    }
}