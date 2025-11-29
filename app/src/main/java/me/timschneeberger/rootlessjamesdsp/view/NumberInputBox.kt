package me.timschneeberger.rootlessjamesdsp.view

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.ViewNumberInputBoxBinding
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.*

class NumberInputBox @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.theme,
    defStyleRes: Int = 0,
) : LinearLayout(context, attrs) {

    private var onValueChangedListener: ((Double) -> Unit)? = null
    private val binding: ViewNumberInputBoxBinding
    private val df = DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ENGLISH))

    init {
        df.maximumFractionDigits = 12
    }

    var customStepScale: ((Double /* current value */, Boolean /* increasing */) -> Double)? = null

    var precision: Int
        get() = df.maximumFractionDigits
        set(value) {
            df.maximumFractionDigits = value
            binding.input.setText(df.format(this.value))
        }

    var min: Double = Double.NEGATIVE_INFINITY
        set(value) {
            field = value
            validateValue()
        }

    var max: Double = Double.POSITIVE_INFINITY
        set(value) {
            field = value
            validateValue()
        }

    var step: Double = 1.0

    var value: Double
        set(newValue) {
            // Preview bug fix
            if (this.isInEditMode) {
                return
            }

            val str = df.format(newValue)
            // avoid recursive afterTextChanged logic re-parsing:
            if (binding.input.text.toString() != str) {
                binding.input.setText(str)
                binding.input.setSelection(str.length)
            }
            onValueChangedListener?.invoke(newValue)
        }
        get() {
            return binding.input.text.toString().toDoubleOrNull() ?: 0.0
        }

// 
    var suffixText: String = ""
        set(value) {
            field = value
            binding.inputLayout.suffixText = value
        }
    var helperText: String = ""
        set(value) {
            field = value
            binding.inputLayout.helperText = value
        }
    var helperTextEnabled: Boolean = false
        set(value) {
            field = value
            binding.inputLayout.isHelperTextEnabled = value
        }
    var hintText: String = ""
        set(value) {
            field = value
            binding.inputLayout.hint = value
        }

    private val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable) {
            if (s.toString().isNotEmpty()) {
                val input = s.toString().toDoubleOrNull() ?: 0.0
                val validated = validateNumber(input)
                // If validateNumber clamps, write clamped value once
                if (validated != null && validated != input) {
                    // This will format and invoke listener
                    value = validated
                } else {
                    // Just propagate the raw double value
                    onValueChangedListener?.invoke(input)
                }
            }
        }
    }

    fun isCurrentValueValid(): Boolean {
        return (binding.input.text?.isNotBlank() ?: false) &&
            validateNumber(value) == null
    }

    private fun validateValue(input: Double = value) {
        val validNumber = validateNumber(input)
        validNumber ?: return
        value = validNumber
    }

    private fun validateNumber(input: Double): Double? {
        return when {
            input > max -> max
            input < min -> min
            else -> null
        }
    }

    init {
        val a = context.obtainStyledAttributes(
            attrs,
            R.styleable.NumberInputBox,
            defStyleAttr,
            defStyleRes
        )
        binding = ViewNumberInputBoxBinding.inflate(LayoutInflater.from(context), this, true)

// Read the current text as Double, with full precision
    val valueAsDouble: Double
        get() = binding.input.text.toString().toDoubleOrNull() ?: 0.0

        precision = a.getInteger(R.styleable.NumberInputBox_floatPrecision, precision)
        step = a.getFloat(R.styleable.NumberInputBox_step, 1f).toDouble()
        value = a.getFloat(R.styleable.NumberInputBox_value, 0f).toDouble()
        min = a.getFloat(R.styleable.NumberInputBox_android_min, min.toFloat()).toDouble()
        max = a.getFloat(R.styleable.NumberInputBox_android_max, max.toFloat()).toDouble()
        suffixText = a.getString(R.styleable.NumberInputBox_suffixText) ?: suffixText
        helperText = a.getString(R.styleable.NumberInputBox_helperText) ?: helperText
        helperTextEnabled = a.getBoolean(
            R.styleable.NumberInputBox_helperTextEnabled,
            helperTextEnabled
        )
        hintText = a.getString(R.styleable.NumberInputBox_hintText) ?: hintText

        a.recycle()

        binding.plus.setOnClickListener {
            val finalStep = customStepScale?.invoke(value, true) ?: step
            val newValue = value + finalStep
            value = validateNumber(newValue) ?: newValue
        }
        binding.minus.setOnClickListener {
            val finalStep = customStepScale?.invoke(value, false) ?: step
            val newValue = value - finalStep
            value = validateNumber(newValue) ?: newValue
        }
/**
 * High-precision version so callers can receive full Double values.
 */
fun setOnValueChangedListenerDouble(listener: ((Double) -> Unit)?) {
    onValueChangedListener = { fValue ->
        listener?.invoke(fValue.toDouble())
    }
}
    override fun onAttachedToWindow() {
        binding.input.addTextChangedListener(textWatcher)
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        binding.input.removeTextChangedListener(textWatcher)
        super.onDetachedFromWindow()
    }

    fun setOnValueChangedListener(listener: ((Float) -> Unit)?) {
        onValueChangedListener = listener
    }
}