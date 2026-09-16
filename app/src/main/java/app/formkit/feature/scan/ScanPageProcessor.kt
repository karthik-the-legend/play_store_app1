package app.formkit.feature.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import app.formkit.core.di.DefaultDispatcher
import app.formkit.core.imaging.AndroidImageCodec
import app.formkit.core.imaging.PixelSize
import app.formkit.core.imaging.SourceImageDecoder
import app.formkit.core.imaging.scan.EdgeFinding
import app.formkit.core.imaging.scan.PageCorners
import app.formkit.core.imaging.scan.PageEdgeFinder
import app.formkit.core.imaging.scan.ScanFilter
import app.formkit.core.imaging.scan.ScanFilters
import app.formkit.core.imaging.scan.luma
import app.formkit.core.imaging.signature.GrayImage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.math.min

/** Finds pages on photos and turns them into flat, filtered page images. */
class ScanPageProcessor @Inject constructor(
    private val decoder: SourceImageDecoder,
    private val codec: AndroidImageCodec,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {

    /** Looks for the page on [photo]. Null when the finder isn't sure. */
    suspend fun findPage(photo: File): EdgeFinding? = withContext(dispatcher) {
        val small = decodeWithin(photo, PageEdgeFinder.WORKING_LONG_EDGE)
        try {
            PageEdgeFinder.find(grayOf(small))
        } finally {
            small.recycle()
        }
    }

    /**
     * The page inside [corners] on [photo], straightened to a rectangle and given [filter], at most
     * [maxLongEdge] pixels on its long side.
     */
    suspend fun render(photo: File, corners: PageCorners, filter: ScanFilter, maxLongEdge: Int): Bitmap = withContext(dispatcher) {
        // Decoding only a little larger than the page will be keeps memory down and gives the
        // bilinear warp enough source pixels without shimmering text.
        val source = decodeWithin(photo, (maxLongEdge * SOURCE_HEADROOM).toInt())
        try {
            val sourceSize = PixelSize(source.width, source.height)
            val size = corners.outputSize(sourceSize, maxLongEdge)
            val page = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            val width = size.width.toFloat()
            val height = size.height.toFloat()
            val matrix = Matrix()
            check(matrix.setPolyToPoly(corners.toPixels(sourceSize), 0, floatArrayOf(0f, 0f, width, 0f, width, height, 0f, height), 0, 4)) {
                "These corners can't be straightened"
            }
            Canvas(page).apply {
                drawColor(Color.WHITE)
                drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            }
            if (filter != ScanFilter.Original) {
                val pixels = IntArray(size.width * size.height)
                page.getPixels(pixels, 0, size.width, 0, 0, size.width, size.height)
                ScanFilters.apply(filter, pixels, size.width, size.height)
                page.setPixels(pixels, 0, size.width, 0, 0, size.width, size.height)
            }
            page
        } finally {
            source.recycle()
        }
    }

    /** [photo] with rotation applied, no more than [longEdge] pixels on its long side. */
    private fun decodeWithin(photo: File, longEdge: Int): Bitmap {
        val limit = min(longEdge, SourceImageDecoder.MAX_DECODE_LONG_EDGE)
        val decoded = decoder.decode(photo, maxLongEdge = limit * 2).bitmap
        val size = PixelSize(decoded.width, decoded.height)
        if (size.longEdge <= limit) return decoded
        val scaled = codec.scale(decoded, size.scaledBy(limit.toDouble() / size.longEdge))
        decoded.recycle()
        return scaled
    }

    private fun grayOf(bitmap: Bitmap): GrayImage {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) pixels[i] = luma(pixels[i])
        return GrayImage(width, height, pixels)
    }

    private companion object {
        const val SOURCE_HEADROOM = 1.5
    }
}
