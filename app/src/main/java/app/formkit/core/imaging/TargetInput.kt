package app.formkit.core.imaging

enum class OptionsError {
    CustomSizeMissing,
    CustomSizeTooSmall,
    CustomSizeTooLarge,
    MinimumTooSmall,
    MinimumAboveMaximum,
    DimensionsMissing,
    DimensionsTooSmall,
    DimensionsTooLarge,
    DpiInvalid,
}

sealed interface OptionsValidation {
    data class Valid(val target: SizeTarget) : OptionsValidation
    data class Invalid(val errors: Set<OptionsError>) : OptionsValidation
}

/** Reads the KB limits and pixel dimensions typed into any tool's text fields. */
object TargetInput {
    /** 1 KB = 1000 bytes. A file under N × 1000 bytes also passes portals that count 1024. */
    const val BYTES_PER_KB = 1000L
    const val MIN_KB = 5
    const val MAX_KB = 50_000
    const val MIN_EDGE = 16
    const val MAX_EDGE = 8000
    const val MAX_DIGITS = 5

    /** The typed limit in KB, or null after recording in [errors] why it can't be used. */
    fun kilobytes(text: String, errors: MutableSet<OptionsError>): Int? {
        val typed = text.toIntOrNull()
        when {
            typed == null -> errors += OptionsError.CustomSizeMissing
            typed < MIN_KB -> errors += OptionsError.CustomSizeTooSmall
            typed > MAX_KB -> errors += OptionsError.CustomSizeTooLarge
            else -> return typed
        }
        return null
    }

    /** The typed dimensions, or null after recording in [errors] why they can't be used. */
    fun dimensions(width: String, height: String, errors: MutableSet<OptionsError>): PixelSize? {
        val typedWidth = width.toIntOrNull()
        val typedHeight = height.toIntOrNull()
        when {
            typedWidth == null || typedHeight == null -> errors += OptionsError.DimensionsMissing
            typedWidth < MIN_EDGE || typedHeight < MIN_EDGE -> errors += OptionsError.DimensionsTooSmall
            typedWidth > MAX_EDGE || typedHeight > MAX_EDGE -> errors += OptionsError.DimensionsTooLarge
            else -> return PixelSize(typedWidth, typedHeight)
        }
        return null
    }
}
