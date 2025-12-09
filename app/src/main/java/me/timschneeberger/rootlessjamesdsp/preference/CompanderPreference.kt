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
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button

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
    val (freqs, gains) = parseFreqsAndGains(value)

    // Update band frequencies globally for CompanderSurface
    for (i in freqs.indices) {
        // mutate the SCALE array so the graph + DSP use your custom freqs
        me.timschneeberger.rootlessjamesdsp.view.CompanderSurface.SCALE[i] = freqs[i]
    }

    // Update gains on the surface
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

    // Remember original stored value so Cancel can revert EVERYTHING (sound + graph)
    val originalValue = getPersistedString(initialValue)
    val (originalFreqs, originalGains) = parseFreqsAndGains(originalValue)

    // Root layout for the whole dialog content
    val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 32, 48, 32)
    }

    // Scrollable area for band fields
    val scroll = ScrollView(ctx)
    val fieldsContainer = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
    }
    scroll.addView(
        fieldsContainer,
        ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )

    val freqInputs = ArrayList<EditText>(bandCount)
    val gainInputs = ArrayList<EditText>(bandCount)

    // Build UI: for each band, editable freq + gain
    for (i in 0 until bandCount) {
        val bandLabel = TextView(ctx).apply {
            text = String.format(Locale.US, "Band %d", i + 1)
            textSize = 14f
        }

        val freqLabel = TextView(ctx).apply {
            text = "Frequency (Hz):"
            textSize = 12f
        }
        val freqInput = EditText(ctx).apply {
            inputType =
                android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(Locale.US, "%.9f", originalFreqs[i]))
        }

        val gainLabel = TextView(ctx).apply {
            text = "Gain (linear):"
            textSize = 12f
        }
        val gainInput = EditText(ctx).apply {
            inputType =
                android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(Locale.US, "%.9f", originalGains[i]))
        }

        fieldsContainer.addView(bandLabel)
        fieldsContainer.addView(freqLabel)
        fieldsContainer.addView(freqInput)
        fieldsContainer.addView(gainLabel)
        fieldsContainer.addView(gainInput)

        freqInputs.add(freqInput)
        gainInputs.add(gainInput)
    }

    // Add the scroll area to root
    root.addView(
        scroll,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f // weight so fields take most of the height
        )
    )

    // Button row – our own Cancel / Save buttons
    val buttonRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        val topPadding = 24
        setPadding(0, topPadding, 0, 0)
    }

    val cancelButton = Button(ctx).apply {
        text = ctx.getString(android.R.string.cancel)
    }
    val okButton = Button(ctx).apply {
        text = ctx.getString(android.R.string.ok)
    }

    // Give the buttons equal width
    buttonRow.addView(
        cancelButton,
        LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        )
    )
    buttonRow.addView(
        okButton,
        LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        )
    )

    root.addView(
        buttonRow,
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )

    // LIVE PREVIEW:
    //  - update prefs → DSP reacts
    //  - update row graph via updateFromPreferences()
    fun applyPreviewFromInputs() {
        val previewFreqs = DoubleArray(bandCount) { idx ->
            freqInputs[idx].text.toString().toDoubleOrNull() ?: originalFreqs[idx]
        }

        val previewGains = DoubleArray(bandCount) { idx ->
            gainInputs[idx].text.toString().toDoubleOrNull() ?: originalGains[idx]
        }

        // Clamp freqs to something sane (optional)
        for (i in 0 until bandCount) {
            if (previewFreqs[i] < 40.0) previewFreqs[i] = 40.0
            if (previewFreqs[i] > 20000.0) previewFreqs[i] = 20000.0
        }

        val previewValue = buildValueFromFreqsAndGains(previewFreqs, previewGains)

        // Let listeners veto; if OK, treat as current preview
        if (callChangeListener(previewValue)) {
            initialValue = previewValue
            persistString(previewValue)
            updateFromPreferences()  // → updates SCALE + setBand() → graph + DSP
        }
    }

    // Attach TextWatcher for live preview on both freq and gain fields
    val watcher = object : android.text.TextWatcher {
        override fun afterTextChanged(s: android.text.Editable?) {
            applyPreviewFromInputs()
        }

        override fun beforeTextChanged(
            s: CharSequence?, start: Int, count: Int, after: Int
        ) { }

        override fun onTextChanged(
            s: CharSequence?, start: Int, before: Int, count: Int
        ) { }
    }

    for (i in 0 until bandCount) {
        freqInputs[i].addTextChangedListener(watcher)
        gainInputs[i].addTextChangedListener(watcher)
    }

    // Build the dialog with our custom root view (no system buttons)
    val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
        .setTitle("Edit compander bands")
        .setMessage("Live preview while you type. Save = keep, Cancel = revert.")
        .setView(root)
        .create()

    // Cancel button: revert prefs + graph + DSP back to original
    cancelButton.setOnClickListener {
        if (callChangeListener(originalValue)) {
            initialValue = originalValue
            persistString(originalValue)
            updateFromPreferences()
        }
        dialog.dismiss()
    }

    // OK/Save button: keep latest preview (already persisted)
    okButton.setOnClickListener {
        // Nothing extra: live preview already stored latest values
        dialog.dismiss()
    }

    dialog.show()
}
}