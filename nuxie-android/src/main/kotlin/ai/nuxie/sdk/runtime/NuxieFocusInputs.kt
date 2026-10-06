package ai.nuxie.sdk.runtime

internal object NuxieFocusLimits {
    const val INPUTS_PER_STEP = 4_096
    const val TEXT_BYTES_PER_INPUT = 1_048_576
    const val TEXT_BYTES_PER_STEP = 4_194_304
}

internal sealed interface NuxieFocusInput {
    data object Next : NuxieFocusInput
    data object Previous : NuxieFocusInput
    data object Clear : NuxieFocusInput
    data class Key(val code: Int, val modifiers: Int, val pressed: Boolean, val repeated: Boolean) : NuxieFocusInput
    data class Text(val value: String) : NuxieFocusInput
}

internal data class NuxieFocusState(val hasFocus: Boolean, val expectsKeyboardInput: Boolean)

internal data class NativeFocusInput(
    val kind: Int,
    val code: Int = 0,
    val modifiers: Int = 0,
    val pressed: Boolean = false,
    val repeated: Boolean = false,
    val text: ByteArray = byteArrayOf(),
)

internal fun encodeFocusInputs(inputs: List<NuxieFocusInput>): List<NativeFocusInput> {
    require(inputs.size <= NuxieFocusLimits.INPUTS_PER_STEP) { "Too many focus inputs" }
    var textBytes = 0
    return inputs.map { input ->
        when (input) {
            NuxieFocusInput.Next -> NativeFocusInput(kind = 0)
            NuxieFocusInput.Previous -> NativeFocusInput(kind = 1)
            NuxieFocusInput.Clear -> NativeFocusInput(kind = 2)
            is NuxieFocusInput.Key -> {
                require(input.code in 0..65_535 && input.modifiers in 0..15) { "Invalid focus key" }
                NativeFocusInput(kind = 3, code = input.code, modifiers = input.modifiers,
                    pressed = input.pressed, repeated = input.repeated)
            }
            is NuxieFocusInput.Text -> {
                val bytes = input.value.encodeToByteArray()
                require(bytes.size <= NuxieFocusLimits.TEXT_BYTES_PER_INPUT &&
                    textBytes <= NuxieFocusLimits.TEXT_BYTES_PER_STEP - bytes.size) { "Focus text exceeds step limits" }
                textBytes += bytes.size
                NativeFocusInput(kind = 4, text = bytes)
            }
        }
    }
}
