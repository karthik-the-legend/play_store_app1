package app.formkit.feature.resize

import app.formkit.core.imaging.OptionsError
import app.formkit.core.imaging.OptionsValidation
import app.formkit.core.imaging.OutputFormat
import app.formkit.core.imaging.PixelSize
import app.formkit.core.imaging.SizeTarget
import app.formkit.core.imaging.TargetInput
import kotlinx.serialization.Serializable

enum class SizePreset(val kilobytes: Int?) {
    Kb20(20), Kb50(50), Kb100(100), Kb200(200), Kb500(500), Mb1(1000), Custom(null),
}

enum class DimensionPreset(val size: PixelSize?) {
    Original(null),
    P200x230(PixelSize(200, 230)),
    P140x160(PixelSize(140, 160)),
    P160x160(PixelSize(160, 160)),
    P300x300(PixelSize(300, 300)),

    /** 35×45 mm at 300 DPI, the Indian passport photo size. */
    P413x531(PixelSize(413, 531)),
    Custom(null),
}

/** Everything the user has set on the resize screen. Text fields keep what was typed. */
@Serializable
data class ResizeOptions(
    val sizePreset: SizePreset = SizePreset.Kb50,
    val customKb: String = "",
    val showMinimum: Boolean = false,
    val minKb: String = "",
    val dimensionPreset: DimensionPreset = DimensionPreset.Original,
    val customWidth: String = "",
    val customHeight: String = "",
    val format: OutputFormat = OutputFormat.Jpeg,
    val allowDownscale: Boolean = true,
) {
    val hasExactSize: Boolean get() = dimensionPreset != DimensionPreset.Original

    fun validate(): OptionsValidation {
        val errors = mutableSetOf<OptionsError>()

        val maxKb = if (sizePreset == SizePreset.Custom) TargetInput.kilobytes(customKb, errors) else sizePreset.kilobytes

        val minimumKb: Int? = if (showMinimum && minKb.isNotEmpty()) {
            val typed = minKb.toIntOrNull()
            when {
                typed == null || typed < 1 -> errors += OptionsError.MinimumTooSmall
                maxKb != null && typed >= maxKb -> errors += OptionsError.MinimumAboveMaximum
            }
            typed
        } else {
            null
        }

        val exactSize = if (dimensionPreset == DimensionPreset.Custom) {
            TargetInput.dimensions(customWidth, customHeight, errors)
        } else {
            dimensionPreset.size
        }

        if (errors.isNotEmpty() || maxKb == null) return OptionsValidation.Invalid(errors)
        return OptionsValidation.Valid(
            SizeTarget(
                maxBytes = maxKb * BYTES_PER_KB,
                minBytes = minimumKb?.let { it * BYTES_PER_KB },
                format = format,
                exactSize = exactSize,
                // Exact dimensions win: a form asking for 200×230 must get exactly that.
                allowDownscale = allowDownscale && exactSize == null,
            ),
        )
    }

    companion object {
        const val BYTES_PER_KB = TargetInput.BYTES_PER_KB
        const val MIN_KB = TargetInput.MIN_KB
        const val MAX_KB = TargetInput.MAX_KB
        const val MIN_EDGE = TargetInput.MIN_EDGE
        const val MAX_EDGE = TargetInput.MAX_EDGE
    }
}

@Serializable
data class SourceImage(
    val path: String,
    val displayName: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
) {
    val size: PixelSize get() = PixelSize(width, height)
}

@Serializable
data class ResizeResult(
    val path: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val quality: Int?,
    val format: OutputFormat,
    val maxBytes: Long,
    val cropped: Boolean,
    val savedUri: String? = null,
    val savedName: String? = null,
) {
    val size: PixelSize get() = PixelSize(width, height)
}

/** What survives process death: the picked photo, the settings and the finished result. */
@Serializable
data class ResizeSession(
    val workspace: String? = null,
    val source: SourceImage? = null,
    val options: ResizeOptions = ResizeOptions(),
    val result: ResizeResult? = null,
)

/**
 * "IMG_2031.HEIC" at 48,213 bytes → "IMG_2031_48KB.jpg". The Photo Picker hides real names and
 * reports bare media IDs like "19.jpg"; those become "FormKit_48KB.jpg" rather than "19_48KB.jpg".
 */
fun exportFileName(sourceName: String, sizeBytes: Long, format: OutputFormat): String {
    val base = sourceName.substringBeforeLast('.')
        .replace(Regex("[^\\p{L}\\p{N}_-]+"), "_")
        .trim('_')
        .take(40)
        .takeUnless { it.isEmpty() || it.all(Char::isDigit) }
        ?: "FormKit"
    val size = if (sizeBytes < ResizeOptions.BYTES_PER_KB) "${sizeBytes}B" else "${sizeBytes / ResizeOptions.BYTES_PER_KB}KB"
    return "${base}_$size.${format.extension}"
}
