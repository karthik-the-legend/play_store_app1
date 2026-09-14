package app.formkit.core.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

class AndroidImageCodec @Inject constructor() : ImageCodec<Bitmap> {

    override fun sizeOf(image: Bitmap): PixelSize = PixelSize(image.width, image.height)

    override fun encode(image: Bitmap, format: OutputFormat, quality: Int): ByteArray {
        val output = ByteArrayOutputStream(INITIAL_BUFFER_BYTES)
        val compressFormat = when (format) {
            OutputFormat.Jpeg -> Bitmap.CompressFormat.JPEG
            OutputFormat.Png -> Bitmap.CompressFormat.PNG
        }
        check(image.compress(compressFormat, quality, output)) { "Encoder rejected the image" }
        return output.toByteArray()
    }

    /**
     * Halves repeatedly before the final resize. A single bilinear pass from a much larger image
     * samples too few source pixels, which makes fine detail like hair and text shimmer.
     */
    override fun scale(image: Bitmap, size: PixelSize): Bitmap {
        var current = image
        while (current.width / 2 >= size.width && current.height / 2 >= size.height) {
            val half = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
            if (current !== image) current.recycle()
            current = half
        }
        if (current.width == size.width && current.height == size.height) {
            return if (current === image) image.copy(image.config ?: Bitmap.Config.ARGB_8888, false) else current
        }
        val result = Bitmap.createScaledBitmap(current, size.width, size.height, true)
        if (current !== image && current !== result) current.recycle()
        return result
    }

    override fun release(image: Bitmap) = image.recycle()

    private companion object {
        const val INITIAL_BUFFER_BYTES = 64 * 1024
    }
}

/** Crops the centre of [source] to [target]'s shape. Returns [source] itself if the shapes already match. */
fun centerCropToAspect(source: Bitmap, target: PixelSize): Bitmap {
    val sourceRatio = source.width.toDouble() / source.height
    val targetRatio = target.aspectRatio
    if (abs(sourceRatio - targetRatio) / targetRatio < ASPECT_TOLERANCE) return source
    val (width, height) = if (sourceRatio > targetRatio) {
        (source.height * targetRatio).roundToInt().coerceIn(1, source.width) to source.height
    } else {
        source.width to (source.width / targetRatio).roundToInt().coerceIn(1, source.height)
    }
    return Bitmap.createBitmap(source, (source.width - width) / 2, (source.height - height) / 2, width, height)
}

/** JPEG has no transparency; without this, transparent pixels come out black. */
fun flattenOntoWhite(source: Bitmap): Bitmap {
    val flattened = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    Canvas(flattened).apply {
        drawColor(Color.WHITE)
        drawBitmap(source, 0f, 0f, null)
    }
    flattened.setHasAlpha(false)
    return flattened
}

private const val ASPECT_TOLERANCE = 0.005
