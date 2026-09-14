package app.formkit.core.imaging

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class OutputFormat(val mimeType: String, val extension: String) {
    Jpeg("image/jpeg", "jpg"),
    Png("image/png", "png"),
}

@Serializable
data class PixelSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "Dimensions must be positive: ${width}x$height" }
    }

    val pixels: Long get() = width.toLong() * height
    val shortEdge: Int get() = min(width, height)
    val longEdge: Int get() = max(width, height)
    val aspectRatio: Double get() = width.toDouble() / height

    fun scaledBy(factor: Double): PixelSize =
        PixelSize(max(1, (width * factor).roundToInt()), max(1, (height * factor).roundToInt()))

    override fun toString(): String = "${width}×$height"
}

/**
 * The image operations the size search needs. Android implements it with [android.graphics.Bitmap];
 * tests implement it with a fake whose output sizes are exact and predictable.
 */
interface ImageCodec<I> {
    fun sizeOf(image: I): PixelSize

    /** Encodes [image]. [quality] (1–100) only affects lossy formats. */
    fun encode(image: I, format: OutputFormat, quality: Int): ByteArray

    /** Returns a new image resized to [size]. The caller releases it. */
    fun scale(image: I, size: PixelSize): I

    fun release(image: I)
}
