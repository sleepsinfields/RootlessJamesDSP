package me.timschneeberger.rootlessjamesdsp.preference

import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.*
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
    ) : super(context, attrs, defStyleAttr, defStyleRes) {
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

        companderView = PreferenceCompanderBinding.bind(holder.itemView).layoutEqualizer
        setEqualizerViewValues(initialValue)
    }

    fun updateFromPreferences() {
        initialValue = getPersistedString(initialValue)
        setEqualizerViewValues(initialValue)
    }

    private fun setEqualizerViewValues(value: String) {
        val gains = parseGains(value)
        gains.forEachIndexed { index, g ->
            companderView?.setBand(index, g)
        }
    }

    // --- helper: how many bands and how to parse/build value string ---

    private val bandCount: Int
        get() = CompanderSurface.SCALE.size

    /**
     * Stored format (from your default):
     *   freq1;freq2;...;freq7;gain1;gain2;...;gain7
     */
    private fun parseGains(value: String): DoubleArray {
        val tokens = value
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val n = bandCount
        val gains = DoubleArray(n) { 0.0 }

        // If we have at least 7 freqs + 7 gains, take the last 7 as gains
        if (tokens.size >= 2 * n) {
            val gainTokens = tokens.takeLast(n)
            for (i in 0 until n) {
                gains[i] = gainTokens[i].toDoubleOrNull() ?: 0.0
            }
            return gains
        }

        // Fallback: if exactly n tokens, assume they are gains
        if (tokens.size == n) {
            for (i in 0 until n) {
                gains[i] = tokens[i].toDoubleOrNull() ?: 0.0
            }
        }

        return gains
    }

    private fun buildValueFromGains(gains: DoubleArray): String {
        val freqs = CompanderSurface.SCALE
        val sb = StringBuilder()

        // Frequencies first
        for (i in freqs.indices) {
            if (i > 0) sb.append(';')
            sb.append(String.format(Locale.US, "%.9f", freqs[i]))
        }

        // Then gains
        for (i in gains.indices) {
            sb.append(';')
            sb.append(String.format(Locale.US, "%.9f", gains[i]))
        }

        return sb.toString()
    }

    // --- dialog binding: hook up the graph + precision edit button ---

    override fun onBindDialogView(view: View) {
        super.onBindDialogView(view)

        // Sync dialog graph with current gains
        val dialogSurface = view.findViewById<CompanderSurface>(R.id.compander_surface)
        val currentValue = getPersistedString(initialValue)
        val gains = parseGains(currentValue)
        dialogSurface?.let { surf ->
            for (i in gains.indices) {
                surf.setBand(i, gains[i])
            }
        }

        // Hook precision editor button
        val editButton = view.findViewById<Button>(R.id.btn_edit_compander_values)
        editButton?.setOnClickListener {
            showPrecisionEditorDialog()
        }
    }

    // --- precision editor: one numeric field per band ---

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

        val freqs = CompanderSurface.SCALE
        val currentValue = getPersistedString(initialValue)
        val currentGains = parseGains(currentValue)
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

                setText(String.format(Locale.US, "%.9f", currentGains[i]))
            }

            layout.addView(label)
            layout.addView(input)
            inputs.add(input)
        }

        AlertDialog.Builder(ctx)
            .setTitle(R.string.compander_enable) // or custom title if you like
            .setMessage("Enter precise gains for each band (linear gain).")
            .setView(scroll)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newGains = DoubleArray(bandCount) { idx ->
                    inputs[idx].text.toString().toDoubleOrNull() ?: currentGains[idx]
                }

                val newValue = buildValueFromGains(newGains)

                // Let listeners veto if needed
                if (callChangeListener(newValue)) {
                    persistString(newValue)
                    initialValue = newValue
                    updateFromPreferences() // refresh the row graph as well
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}