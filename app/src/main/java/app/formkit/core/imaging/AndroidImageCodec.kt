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

    override fun scale(image: Bitmap, size: PixelSize): Bitmap = scale(image, size, sharpen = false)

    /**
     * Halves natively while the image is at least twice the size (an exact 2×2 average, and fast),
     * then makes the last, uneven step with [Resampler], which keeps far more detail than Android's
     * bilinear scaling. With [sharpen], a shrunk opaque photo also gets a light unsharp mask.
     */
    fun scale(image: Bitmap, size: PixelSize, sharpen: Boolean): Bitmap {
        val reduction = image.width.toDouble() / size.width
        var current = image
        while (current.width / 2 >= size.width && current.height / 2 >= size.height) {
            val half = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
            if (current !== image) current.recycle()
            current = half
        }
        var result = if (current.width == size.width && current.height == size.height) {
            if (current === image) image.copy(Bitmap.Config.ARGB_8888, true) else current
        } else {
            resample(current, size).also { if (current !== image) current.recycle() }
        }
        if (sharpen && reduction >= SHARPEN_FROM_REDUCTION && !result.hasAlpha()) {
            // Halving alone can land on the size, and Android's scaled bitmaps are read-only.
            if (!result.isMutable) result = result.copy(Bitmap.Config.ARGB_8888, true).also { result.recycle() }
            sharpenInPlace(result)
        }
        return result
    }

    /** This codec, but photos it shrinks come out lightly sharpened: for photos, not signatures or text. */
    val forPhotos: ImageCodec<Bitmap> = object : ImageCodec<Bitmap> by this {
        override fun scale(image: Bitmap, size: PixelSize): Bitmap = scale(image, size, sharpen = true)
    }

    /** Light unsharp mask on an opaque, mutable bitmap (see [Resampler.sharpen]). */
    fun sharpenInPlace(bitmap: Bitmap) {
        val width = bitmap.width
        Resampler.sharpen(
            width,
            bitmap.height,
            source = { y, into -> bitmap.getPixels(into, 0, width, 0, y, width, 1) },
            sink = { y, row -> bitmap.setPixels(row, 0, width, 0, y, width, 1) },
        )
    }

    private fun resample(source: Bitmap, size: PixelSize): Bitmap {
        val result = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        val sourceWidth = source.width
        val hasAlpha = source.hasAlpha()
        Resampler.resize(
            sourceWidth,
            source.height,
            size.width,
            size.height,
            hasAlpha,
            source = { y, into -> source.getPixels(into, 0, sourceWidth, 0, y, sourceWidth, 1) },
            sink = { y, row -> result.setPixels(row, 0, size.width, 0, y, size.width, 1) },
        )
        result.setHasAlpha(hasAlpha)
        return result
    }

    override fun release(image: Bitmap) = image.recycle()

    private companion object {
        const val INITIAL_BUFFER_BYTES = 64 * 1024

        /** Sharpening only helps once shrinking has softened the photo; a light trim needs none. */
        const val SHARPEN_FROM_REDUCTION = 1.25
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
