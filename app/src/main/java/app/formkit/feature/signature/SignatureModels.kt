package app.formkit.feature.signature

import app.formkit.core.imaging.OptionsError
import app.formkit.core.imaging.OptionsValidation
import app.formkit.core.imaging.OutputFormat
import app.formkit.core.imaging.PixelSize
import app.formkit.core.imaging.SizeTarget
import app.formkit.core.imaging.TargetInput
import app.formkit.core.imaging.signature.InkBounds
import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.roundToInt

enum class SignatureMode { Photo, Draw }

enum class SignatureBackground(val format: OutputFormat) {
    White(OutputFormat.Jpeg),
    Transparent(OutputFormat.Png),
}

enum class SignatureSizePreset(val kilobytes: Int?) {
    NoLimit(null), Kb10(10), Kb20(20), Kb50(50), Custom(null),
}

enum class SignatureDimensionPreset(val size: PixelSize?) {
    /** The crop's own size. */
    Fit(null),
    P140x60(PixelSize(140, 60)),
    P160x60(PixelSize(160, 60)),
    Custom(null),
}

/** Pen thickness as a fraction of the drawing pad's width. */
enum class StrokeWidth(val fraction: Float) {
    Thin(0.006f), Medium(0.010f), Bold(0.016f),
}

@Serializable
data class SignatureOptions(
    val inkStrength: Float = DEFAULT_INK_STRENGTH,
    val strokeWidth: StrokeWidth = StrokeWidth.Medium,
    val background: SignatureBackground = SignatureBackground.White,
    val sizePreset: SignatureSizePreset = SignatureSizePreset.Kb20,
    val customKb: String = "",
    val dimensionPreset: SignatureDimensionPreset = SignatureDimensionPreset.Fit,
    val customWidth: String = "",
    val customHeight: String = "",
) {
    val hasSizeLimit: Boolean get() = sizePreset != SignatureSizePreset.NoLimit

    fun validate(): OptionsValidation {
        val errors = mutableSetOf<OptionsError>()
        val maxBytes: Long? = when (sizePreset) {
            SignatureSizePreset.NoLimit -> NO_LIMIT_BYTES
            SignatureSizePreset.Custom -> TargetInput.kilobytes(customKb, errors)?.let { it * TargetInput.BYTES_PER_KB }
            else -> sizePreset.kilobytes?.let { it * TargetInput.BYTES_PER_KB }
        }
        val exactSize = if (dimensionPreset == SignatureDimensionPreset.Custom) {
            TargetInput.dimensions(customWidth, customHeight, errors)
        } else {
            dimensionPreset.size
        }
        if (errors.isNotEmpty() || maxBytes == null) return OptionsValidation.Invalid(errors)
        return OptionsValidation.Valid(
            SizeTarget(
                maxBytes = maxBytes,
                format = background.format,
                exactSize = exactSize,
                allowDownscale = exactSize == null,
            ),
        )
    }

    companion object {
        const val DEFAULT_INK_STRENGTH = 0.5f

        /** "No limit" still needs a number; no signature comes anywhere near it. */
        const val NO_LIMIT_BYTES = 50_000_000L
    }
}

enum class CropHandle { TopLeft, TopRight, BottomLeft, BottomRight, Move }

/** A crop in fractions of the image, so it still fits if the image is decoded at another size. */
@Serializable
data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {

    fun toBounds(width: Int, height: Int): InkBounds {
        val pixelLeft = (left * width).toInt().coerceIn(0, width - 1)
        val pixelTop = (top * height).toInt().coerceIn(0, height - 1)
        val pixelRight = ceil(right * width).toInt().coerceIn(pixelLeft + 1, width)
        val pixelBottom = ceil(bottom * height).toInt().coerceIn(pixelTop + 1, height)
        return InkBounds(pixelLeft, pixelTop, pixelRight, pixelBottom)
    }

    /** Drags a corner or the whole frame, keeping it inside the image and at least [MIN_SIZE] across. */
    fun dragged(handle: CropHandle, dx: Float, dy: Float): CropRect = when (handle) {
        CropHandle.TopLeft -> copy(
            left = (left + dx).coerceIn(0f, right - MIN_SIZE),
            top = (top + dy).coerceIn(0f, bottom - MIN_SIZE),
        )
        CropHandle.TopRight -> copy(
            right = (right + dx).coerceIn(left + MIN_SIZE, 1f),
            top = (top + dy).coerceIn(0f, bottom - MIN_SIZE),
        )
        CropHandle.BottomLeft -> copy(
            left = (left + dx).coerceIn(0f, right - MIN_SIZE),
            bottom = (bottom + dy).coerceIn(top + MIN_SIZE, 1f),
        )
        CropHandle.BottomRight -> copy(
            right = (right + dx).coerceIn(left + MIN_SIZE, 1f),
            bottom = (bottom + dy).coerceIn(top + MIN_SIZE, 1f),
        )
        CropHandle.Move -> {
            val cropWidth = right - left
            val cropHeight = bottom - top
            val newLeft = (left + dx).coerceIn(0f, 1f - cropWidth)
            val newTop = (top + dy).coerceIn(0f, 1f - cropHeight)
            CropRect(newLeft, newTop, newLeft + cropWidth, newTop + cropHeight)
        }
    }

    companion object {
        const val MIN_SIZE = 0.05f
        val Full = CropRect(0f, 0f, 1f, 1f)

        fun of(bounds: InkBounds, width: Int, height: Int) = CropRect(
            left = bounds.left.toFloat() / width,
            top = bounds.top.toFloat() / height,
            right = bounds.right.toFloat() / width,
            bottom = bounds.bottom.toFloat() / height,
        )
    }
}

/** One pen stroke. Points are x,y pairs in fractions of the pad; width is a fraction of its width. */
@Serializable
data class DrawnStroke(val width: Float, val points: List<Float>)

@Serializable
data class Drawing(val strokes: List<DrawnStroke> = emptyList()) {
    val isEmpty: Boolean get() = strokes.isEmpty()

    operator fun plus(stroke: DrawnStroke) = copy(strokes = strokes + stroke)

    fun undo() = copy(strokes = strokes.dropLast(1))

    companion object {
        /** The pad is 2.5 times wider than it is tall, roughly the shape of a signature box. */
        const val ASPECT_RATIO = 2.5f
        const val MIN_POINT_DISTANCE = 0.003f
    }
}

/**
 * Adds a point to a stroke unless it's too close to the previous one to matter. Coordinates are
 * rounded to four decimal places, which is finer than any pad pixel and keeps saved state small.
 */
fun MutableList<Float>.addStrokePoint(x: Float, y: Float, minDistance: Float = Drawing.MIN_POINT_DISTANCE): Boolean {
    val roundedX = roundToFourPlaces(x.coerceIn(0f, 1f))
    val roundedY = roundToFourPlaces(y.coerceIn(0f, 1f))
    if (size >= 2) {
        val dx = roundedX - this[size - 2]
        val dy = roundedY - this[size - 1]
        if (dx * dx + dy * dy < minDistance * minDistance) return false
    }
    add(roundedX)
    add(roundedY)
    return true
}

private fun roundToFourPlaces(value: Float): Float = (value * 10_000f).roundToInt() / 10_000f

@Serializable
data class SignatureSource(val path: String, val displayName: String)

@Serializable
data class SignatureResult(
    val path: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val format: OutputFormat,
    /** Null when the user chose no size limit. */
    val maxBytes: Long?,
    val savedUri: String? = null,
    val savedName: String? = null,
) {
    val size: PixelSize get() = PixelSize(width, height)
}

/** What survives process death. The decoded photo itself is rebuilt from [source]. */
@Serializable
data class SignatureSession(
    val workspace: String? = null,
    val mode: SignatureMode? = null,
    val source: SignatureSource? = null,
    /** Where the camera app is writing, while it's open. */
    val pendingCapture: String? = null,
    val crop: CropRect? = null,
    val drawing: Drawing = Drawing(),
    val options: SignatureOptions = SignatureOptions(),
    val result: SignatureResult? = null,
)

fun signatureFileName(sizeBytes: Long, format: OutputFormat): String {
    val size = if (sizeBytes < TargetInput.BYTES_PER_KB) "${sizeBytes}B" else "${sizeBytes / TargetInput.BYTES_PER_KB}KB"
    return "Signature_$size.${format.extension}"
}
