package app.formkit.core.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renders pages of an unprotected PDF with Android's own renderer. Not thread-safe: use one
 * instance from one coroutine at a time. Only one page is ever open, so memory stays at one bitmap.
 *
 * @throws java.io.IOException if the file isn't a PDF Android can read
 * @throws SecurityException if the file is password protected (open it with [PdfDocuments] first)
 */
class PdfRasterizer(file: File) : Closeable {

    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try {
        PdfRenderer(descriptor)
    } catch (e: Throwable) {
        descriptor.close()
        throw e
    }

    val pageCount: Int get() = renderer.pageCount

    /** Page width and height in points (1/72 inch). */
    fun pageSize(index: Int): Pair<Float, Float> = renderer.openPage(index).use { it.width.toFloat() to it.height.toFloat() }

    /**
     * Renders page [index] at [dpi], but never larger than [maxLongEdge] pixels on its long side.
     * Transparent areas come out white, the way the page looks on paper.
     */
    fun render(index: Int, dpi: Float, maxLongEdge: Int): Bitmap = renderer.openPage(index).use { page ->
        val scale = min(dpi / POINTS_PER_INCH, maxLongEdge / max(page.width, page.height).toFloat())
        val width = max(1, (page.width * scale).roundToInt())
        val height = max(1, (page.height * scale).roundToInt())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val matrix = Matrix().apply { setScale(width / page.width.toFloat(), height / page.height.toFloat()) }
        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        bitmap
    }

    /** A small preview of page [index], at most [maxLongEdge] pixels on its long side. */
    fun thumbnail(index: Int, maxLongEdge: Int): Bitmap = render(index, dpi = Float.MAX_VALUE, maxLongEdge = maxLongEdge)

    override fun close() {
        renderer.close()
        descriptor.close()
    }

    private companion object {
        const val POINTS_PER_INCH = 72f
    }
}
