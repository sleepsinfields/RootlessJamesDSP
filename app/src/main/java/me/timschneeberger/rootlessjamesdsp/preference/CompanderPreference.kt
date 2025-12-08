package me.timschneeberger.rootlessjamesdsp.preference

import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.DialogPreference
import androidx.preference.PreferenceViewHolder
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.PreferenceCompanderBinding
import me.timschneeberger.rootlessjamesdsp.view.CompanderSurface
import java.util.Locale

class CompanderPreference : DialogPreference {

    private var companderView: CompanderSurface? = null
    var initialValue: String = ""

    constructor(
        context: Context, attrs: AttributeSet?,
        defStyleAttr: Int
    ) : this(context, attrs, defStyleAttr, 0)

    constructor(
        context: Context, attrs: AttributeSet?
    ) : this(context, attrs, androidx.preference.R.attr.preferenceStyle)

    constructor(
        context: Context
    ) : this(context, null)

    constructor(
        context: Context, attrs: AttributeSet?, defStyleAttr: Int,
        defStyleRes: Int
    ) : super(context, attrs, defStyleRes, defStyleRes) {
        layoutResource = R.layout.preference_compander
        dialogLayoutResource = R.layout.preference_compander_dialog

        this.positiveButtonText = context.getString(android.R.string.ok)
        this.negativeButtonText = context.getString(android.R.string.cancel)
    }

    // --- core persistence ---

    override fun onSetInitialValue(defaultValue: Any?) {
        initialValue = getPersistedString(defaultValue as? String ?: "")
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any {
        return a.getString(index).toString()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val binding = PreferenceCompanderBinding.bind(holder.itemView)
        companderView = binding.layoutEqualizer

        // draw current gains into the small row graph
        setEqualizerViewValues(initialValue)

        // precision edit button in the row
        binding.btnEditCompanderValues.setOnClickListener {
            showPrecisionEditorDialog()
        }
    }

    fun updateFromPreferences() {
        initialValue = getPersistedString(initialValue)
        setEqualizerViewValues(initialValue)
    }

    private fun setEqualizerViewValues(value: String) {
        val (_, gains) = parseFreqsAndGains(value)
        gains.forEachIndexed { index, g ->
            companderView?.setBand(index, g)
        }
    }

    // --- parsing/building the stored string ---

    private val bandCount: Int
        get() = CompanderSurface.SCALE.size

    /**
     * Stored format (from your default):
     *   freq1;freq2;...;freq7;gain1;gain2;...;gain7
     */
    private fun parseFreqsAndGains(value: String): Pair<DoubleArray, DoubleArray> {
        val tokens = value
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val n = bandCount
        val freqs = DoubleArray(n) { i ->
            CompanderSurface.SCALE[i]
        }
        val gains = DoubleArray(n) { 0.0 }

        if (tokens.size >= 2 * n) {
            // first n = freqs, last n = gains
            for (i in 0 until n) {
                freqs[i] = tokens[i].toDoubleOrNull() ?: CompanderSurface.SCALE[i]
            }
            for (i in 0 until n) {
                gains[i] = tokens[n + i].toDoubleOrNull() ?: 0.0
            }
            return freqs to gains
        }

        // fallback: if exactly n tokens, treat them as gains only
        if (tokens.size == n) {
            for (i in 0 until n) {
                gains[i] = tokens[i].toDoubleOrNull() ?: 0.0
            }
        }

        return freqs to gains
    }

    private fun buildValueFromFreqsAndGains(freqs: DoubleArray, gains: DoubleArray): String {
        val n = bandCount
        val sb = StringBuilder()

        // frequencies first
        for (i in 0 until n) {
            if (i > 0) sb.append(';')
            sb.append(String.format(Locale.US, "%.9f", freqs[i]))
        }
        // then gains
        for (i in 0 until n) {
            sb.append(';')
            sb.append(String.format(Locale.US, "%.9f", gains[i]))
        }

        return sb.toString()
    }

    // --- precision editor: dialog with one field per band ---

    private fun showPrecisionEditorDialog() {
        val ctx = context

        val scroll = ScrollView(ctx)
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }
        scroll.addView(
            layout,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val (freqs, gains) = parseFreqsAndGains(getPersistedString(initialValue))
        val inputs = ArrayList<EditText>(bandCount)

        for (i in 0 until bandCount) {
            val label = TextView(ctx).apply {
                text = String.format(Locale.US, "%.0f Hz gain:", freqs[i])
            }

            val input = EditText(ctx).apply {
                inputType =
                    android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_SIGNED or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL

                setText(String.format(Locale.US, "%.9f", gains[i]))
            }

            layout.addView(label)
            layout.addView(input)
            inputs.add(input)
        }

        AlertDialog.Builder(ctx)
            .setTitle(R.string.compander_enable) // or custom title
            .setMessage("Enter precise gains for each band (linear, not dB).")
            .setView(scroll)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newGains = DoubleArray(bandCount) { idx ->
                    inputs[idx].text.toString().toDoubleOrNull() ?: gains[idx]
                }

                val newValue = buildValueFromFreqsAndGains(freqs, newGains)

                if (callChangeListener(newValue)) {
                    persistString(newValue)
                    initialValue = newValue
                    updateFromPreferences() // refresh the row graph
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}