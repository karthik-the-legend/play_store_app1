package app.formkit.core.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlin.math.max

class UnsupportedImageException(message: String) : IOException(message)

class DecodedImage(
    val bitmap: Bitmap,
    /** Dimensions of the file as the user sees it (rotation applied), before any downsampling. */
    val originalSize: PixelSize,
)

/**
 * Decodes photos with rotation applied and without running out of memory on 50 MP input.
 *
 * `BitmapFactory` plus `ExifInterface` on every API level keeps one code path. HEIC decodes
 * from Android 9, the version where the platform decoder added it.
 */
class SourceImageDecoder @Inject constructor() {

    /** Reads dimensions without decoding pixels. */
    fun readSize(file: File): PixelSize = orient(readBounds(file), readOrientation(file))

    fun decode(file: File, maxLongEdge: Int = MAX_DECODE_LONG_EDGE): DecodedImage {
        val bounds = readBounds(file)
        val orientation = readOrientation(file)

        var sampleSize = 1
        while (max(bounds.width, bounds.height) / sampleSize > maxLongEdge) sampleSize *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.path, options)
            ?: throw UnsupportedImageException("Could not decode ${file.name}")

        return DecodedImage(applyOrientation(decoded, orientation), orient(bounds, orientation))
    }

    private fun readBounds(file: File): PixelSize {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw UnsupportedImageException("Not a readable image: ${file.name}")
        }
        return PixelSize(options.outWidth, options.outHeight)
    }

    private fun readOrientation(file: File): Orientation = try {
        val exif = ExifInterface(file)
        Orientation(exif.rotationDegrees, exif.isFlipped)
    } catch (_: IOException) {
        Orientation.None
    }

    private fun orient(size: PixelSize, orientation: Orientation): PixelSize =
        if (orientation.degrees % 180 != 0) PixelSize(size.height, size.width) else size

    private fun applyOrientation(bitmap: Bitmap, orientation: Orientation): Bitmap {
        if (orientation == Orientation.None) return bitmap
        val matrix = Matrix().apply {
            // ExifInterface reports flips as a horizontal mirror followed by the rotation.
            if (orientation.flipped) postScale(-1f, 1f)
            postRotate(orientation.degrees.toFloat())
        }
        val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (oriented !== bitmap) bitmap.recycle()
        return oriented
    }

    private data class Orientation(val degrees: Int, val flipped: Boolean) {
        companion object {
            val None = Orientation(0, false)
        }
    }

    companion object {
        /** Big enough for any form upload; small enough that a 50 MP photo decodes in ~50 MB. */
        const val MAX_DECODE_LONG_EDGE = 4096
    }
}
