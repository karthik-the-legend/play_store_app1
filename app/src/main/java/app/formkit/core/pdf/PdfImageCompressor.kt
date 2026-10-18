package app.formkit.core.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import app.formkit.core.di.DefaultDispatcher
import app.formkit.core.imaging.AndroidImageCodec
import app.formkit.core.imaging.OutputFormat
import app.formkit.core.imaging.PixelSize
import app.formkit.core.imaging.flattenOntoWhite
import com.tom_roush.pdfbox.contentstream.PDFStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.contentstream.operator.state.Concatenate
import com.tom_roush.pdfbox.contentstream.operator.state.Restore
import com.tom_roush.pdfbox.contentstream.operator.state.Save
import com.tom_roush.pdfbox.contentstream.operator.state.SetGraphicsStateParameters
import com.tom_roush.pdfbox.contentstream.operator.state.SetMatrix
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.util.IdentityHashMap
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Shrinks a PDF by recompressing only the photos in it. Text, lines and drawings stay exactly as
 * they were: sharp at any zoom, and still selectable and searchable. That's what desktop PDF
 * compressors do, and it's why they keep text crisp where turning pages into images blurs it.
 *
 * Each photo is cut down to the pixels it needs for the size it's shown on the page at a given
 * density, then saved as a JPEG at a given quality. A list of [Level]s runs from gentle to harsh;
 * a binary search finds the gentlest that fits. A PDF whose size isn't in its photos (fonts, or
 * pages that are vector drawings) can't be shrunk this way: [compress] says so with null, and
 * [PdfCompressor] turns pages into images instead.
 */
class PdfImageCompressor @Inject constructor(
    private val documents: PdfDocuments,
    private val codec: AndroidImageCodec,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {

    data class Level(val dpi: Int, val quality: Int)

    /** [onProgress] gets the photo being done and how many there are, once per attempt. */
    suspend fun compress(
        source: File,
        maxBytes: Long,
        workDir: File,
        output: File,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): CompressOutcome.Done? = withContext(dispatcher) {
        workDir.mkdirs()
        val photoCount = documents.edit(source) { document -> ImageSurvey.of(document).count { recompressible(it.image) } }
        Log.d(TAG, "$photoCount photos to recompress")
        if (photoCount == 0) return@withContext null

        // The harshest level first: if even that's too big, the photos aren't where the size is.
        val attempts = HashMap<Int, File>()
        suspend fun attempt(index: Int): Long {
            val file = File(workDir, "level-$index.pdf")
            documents.edit(source) { document -> rewrite(document, LEVELS[index], onProgress, file) }
            attempts[index] = file
            Log.d(TAG, "${LEVELS[index]}: ${file.length()} bytes for a $maxBytes byte limit")
            return file.length()
        }

        try {
            val harshest = LEVELS.lastIndex
            if (attempt(harshest) > maxBytes) return@withContext null
            var fits = harshest
            var low = 0
            var high = harshest - 1
            while (low <= high) {
                val middle = (low + high) / 2
                if (attempt(middle) <= maxBytes) {
                    fits = middle
                    high = middle - 1
                } else {
                    low = middle + 1
                }
            }
            val chosen = checkNotNull(attempts[fits])
            output.parentFile?.mkdirs()
            chosen.copyTo(output, overwrite = true)
            val pageCount = documents.edit(output) { it.numberOfPages }
            CompressOutcome.Done(output.length(), pageCount, keptText = true)
        } finally {
            attempts.values.forEach { it.delete() }
        }
    }

    private suspend fun rewrite(document: PDDocument, level: Level, onProgress: (Int, Int) -> Unit, output: File) {
        val photos = ImageSurvey.of(document).filter { recompressible(it.image) }
        photos.forEachIndexed { index, use ->
            currentCoroutineContext().ensureActive()
            onProgress(index + 1, photos.size)
            // A photo PdfBox can't read stays as it was; the rest still shrink.
            try {
                recompress(use, level)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "Left a photo as it was", e)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Left a photo as it was", e)
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "Left a photo as it was: too big to decode", e)
            }
        }
        output.parentFile?.mkdirs()
        document.save(output)
    }

    private fun recompress(use: ImageUse, level: Level) {
        val image = use.image
        val stream = image.cosObject
        val target = ImageDownsampling.targetSize(
            PixelSize(image.width, image.height),
            use.shownWidthPoints,
            use.shownHeightPoints,
            level.dpi,
        )
        // Decode no bigger than needed: halving while decoding saves memory on big scans.
        val subsampling = ImageDownsampling.subsampling(PixelSize(image.width, image.height), target)
        val components = if (colours(stream) == Colours.Gray) 1 else RGB_COMPONENTS
        var bitmap: Bitmap = decodePhoto(stream, image.width, image.height, components, subsampling) ?: return
        try {
            if (bitmap.hasAlpha()) bitmap = flattenOntoWhite(bitmap).also { bitmap.recycle() }
            if (bitmap.width != target.width || bitmap.height != target.height) {
                bitmap = codec.scale(bitmap, target).also { if (it !== bitmap) bitmap.recycle() }
            }
            val jpeg = codec.encode(bitmap, OutputFormat.Jpeg, level.quality)
            // Only replace a photo with something meaningfully smaller.
            if (jpeg.size >= stream.length * KEEP_IF_SAVING_UNDER) return
            stream.createRawOutputStream().use { it.write(jpeg) }
            stream.setItem(COSName.FILTER, COSName.DCT_DECODE)
            stream.removeItem(COSName.DECODE_PARMS)
            stream.removeItem(COSName.DP)
            stream.setInt(COSName.WIDTH, bitmap.width)
            stream.setInt(COSName.HEIGHT, bitmap.height)
            stream.setInt(COSName.BITS_PER_COMPONENT, 8)
            // Android writes colour JPEGs, so grey becomes RGB; a colour profile stays with its photo.
            if (colours(stream) != Colours.ProfiledRgb) stream.setItem(COSName.COLORSPACE, COSName.DEVICERGB)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * The photo's own pixels, shrunk by [subsampling] while decoding. A transparency mask stays
     * beside the photo, untouched (the viewer combines them), so it isn't decoded into it.
     *
     * PdfBox's own image reader isn't used: on Android it crashes when asked for a smaller decode
     * and returns grey photos blank. A JPEG goes straight to Android's decoder; anything else is
     * unpacked by PdfBox's filters (deflate and the like, which are simple and reliable) and the
     * samples are averaged in blocks here, a row at a time, so a big scan never sits in memory whole.
     */
    private fun decodePhoto(stream: COSStream, width: Int, height: Int, components: Int, subsampling: Int): Bitmap? {
        val filters = filtersOf(stream)
        if (filters == listOf(COSName.DCT_DECODE)) {
            val options = BitmapFactory.Options().apply {
                inSampleSize = subsampling
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            return stream.createRawInputStream().use { BitmapFactory.decodeStream(it, null, options) }
        }
        if (COSName.DCT_DECODE in filters || stream.getInt(COSName.BITS_PER_COMPONENT) != 8) return null

        val outWidth = max(1, width / subsampling)
        val outHeight = max(1, height / subsampling)
        val bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val row = ByteArray(width * components)
        val sums = IntArray(outWidth * components)
        val out = IntArray(outWidth)
        val block = min(subsampling, width) * min(subsampling, height)
        DataInputStream(BufferedInputStream(stream.createInputStream())).use { samples ->
            for (y in 0 until outHeight) {
                sums.fill(0)
                repeat(min(subsampling, height)) {
                    samples.readFully(row)
                    for (x in 0 until outWidth) {
                        for (dx in 0 until min(subsampling, width)) {
                            val source = (x * subsampling + dx) * components
                            for (c in 0 until components) sums[x * components + c] += row[source + c].toInt() and 0xFF
                        }
                    }
                }
                for (x in 0 until outWidth) {
                    val o = x * components
                    val r = sums[o] / block
                    val g = if (components == 1) r else sums[o + 1] / block
                    val b = if (components == 1) r else sums[o + 2] / block
                    out[x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                bitmap.setPixels(out, 0, outWidth, 0, y, outWidth, 1)
            }
        }
        bitmap.setHasAlpha(false)
        return bitmap
    }

    private fun filtersOf(stream: COSStream): List<COSName> = when (val filter = stream.getDictionaryObject(COSName.FILTER)) {
        is COSName -> listOf(filter)
        is COSArray -> filter.mapNotNull { it as? COSName }
        else -> emptyList()
    }

    /**
     * Photos worth recompressing: big enough to matter, and nothing whose meaning a JPEG would
     * change. One-bit art, stencils and colour-key masks (exact colours meaning "transparent") are
     * left alone, as are formats that are already compact for what they hold (fax and JBIG2 for
     * scanned text) or that PdfBox can't decode on Android (JPEG 2000). A photo with a separate
     * transparency mask is fine: the photo is recompressed and the mask kept as it was.
     */
    private fun recompressible(image: PDImageXObject): Boolean {
        val stream: COSStream = image.cosObject
        if (stream.length < MIN_PHOTO_BYTES) return false
        if (image.isStencil || image.bitsPerComponent == 1) return false
        if (stream.getDictionaryObject(COSName.MASK) is COSArray || stream.containsKey(COSName.F)) return false
        // A Decode array (an inverted grey, say) isn't applied on every decoding path of the Android port.
        if (stream.containsKey(COSName.DECODE)) return false
        val filters = filtersOf(stream)
        if (filters.any { it == COSName.JPX_DECODE || it == COSName.JBIG2_DECODE || it == COSName.CCITTFAX_DECODE }) return false
        return colours(stream) != null
    }

    private enum class Colours { Gray, Rgb, ProfiledRgb }

    /**
     * Only grey and RGB photos are touched, read from the PDF's own entry. PdfBox's Android port
     * doesn't fail on other colours (CMYK, palettes, spot inks): it reads them as RGB, which would
     * quietly ruin the photo. An RGB photo with a colour profile keeps it, since its numbers don't
     * change meaning.
     */
    private fun colours(stream: COSStream): Colours? = when (val space = stream.getDictionaryObject(COSName.COLORSPACE)) {
        COSName.DEVICEGRAY -> Colours.Gray
        COSName.DEVICERGB -> Colours.Rgb
        is COSArray -> {
            val profile = space.takeIf { it.size() == 2 && it.getName(0) == ICC_BASED }?.getObject(1) as? COSStream
            if (profile?.getInt(COSName.N) == RGB_COMPONENTS) Colours.ProfiledRgb else null
        }
        else -> null
    }

    private class ImageUse(val image: PDImageXObject, var shownWidthPoints: Float, var shownHeightPoints: Float)

    /** Every photo drawn on every page (inside forms too), with the largest size it's shown at. */
    private class ImageSurvey private constructor() : PDFStreamEngine() {
        private val uses = IdentityHashMap<COSStream, ImageUse>()

        init {
            addOperator(Concatenate())
            addOperator(Save())
            addOperator(Restore())
            addOperator(SetGraphicsStateParameters())
            addOperator(SetMatrix())
        }

        override fun processOperator(operator: Operator, operands: List<COSBase>) {
            if (operator.name != DRAW_OBJECT) {
                super.processOperator(operator, operands)
                return
            }
            val name = operands.firstOrNull() as? COSName ?: return
            when (val xobject = runCatching { resources?.getXObject(name) }.getOrNull()) {
                is PDImageXObject -> {
                    val matrix = graphicsState.currentTransformationMatrix
                    val width = abs(matrix.scalingFactorX)
                    val height = abs(matrix.scalingFactorY)
                    val use = uses.getOrPut(xobject.cosObject) { ImageUse(xobject, 0f, 0f) }
                    use.shownWidthPoints = max(use.shownWidthPoints, width)
                    use.shownHeightPoints = max(use.shownHeightPoints, height)
                }
                is PDFormXObject -> showForm(xobject)
                else -> Unit
            }
        }

        companion object {
            private const val DRAW_OBJECT = "Do"

            fun of(document: PDDocument): Collection<ImageUse> {
                val survey = ImageSurvey()
                for (page in document.pages) {
                    try {
                        survey.processPage(page)
                    } catch (_: IOException) {
                        // A damaged page's photos are left as they are.
                    }
                }
                return survey.uses.values
            }
        }
    }

    companion object {
        /** Gentle to harsh. Below 50 DPI photos stop being recognisable, so it stops there. */
        val LEVELS = listOf(
            Level(dpi = 220, quality = 85),
            Level(dpi = 200, quality = 80),
            Level(dpi = 170, quality = 76),
            Level(dpi = 150, quality = 72),
            Level(dpi = 150, quality = 64),
            Level(dpi = 130, quality = 60),
            Level(dpi = 110, quality = 55),
            Level(dpi = 96, quality = 50),
            Level(dpi = 84, quality = 45),
            Level(dpi = 72, quality = 40),
            Level(dpi = 60, quality = 35),
            Level(dpi = 50, quality = 30),
        )

        /** Under this, a photo's bytes don't move the total; recompressing it would only lose detail. */
        private const val MIN_PHOTO_BYTES = 8 * 1024L
        private const val KEEP_IF_SAVING_UNDER = 0.95
        private const val ICC_BASED = "ICCBased"
        private const val TAG = "PdfImageCompressor"
        private const val RGB_COMPONENTS = 3
    }
}

/** How many pixels a photo needs for the size it's shown at. */
internal object ImageDownsampling {
    private const val POINTS_PER_INCH = 72.0

    /** Within this much of the target, a photo isn't worth resampling. */
    private const val SLACK = 1.05

    /**
     * The photo's size at [dpi] for the size it's shown at, keeping its shape, and never larger
     * than it already is. A photo shown at zero size (never visible) keeps a token 16 pixels.
     */
    fun targetSize(image: PixelSize, shownWidthPoints: Float, shownHeightPoints: Float, dpi: Int): PixelSize {
        val neededWidth = ceil(shownWidthPoints / POINTS_PER_INCH * dpi)
        val neededHeight = ceil(shownHeightPoints / POINTS_PER_INCH * dpi)
        // Keep the shape: the side that needs relatively more pixels decides.
        val factor = max(neededWidth / image.width, neededHeight / image.height).coerceAtLeast(MIN_EDGE / image.shortEdge.toDouble())
        if (factor * SLACK >= 1.0) return image
        return image.scaledBy(factor)
    }

    /** The largest power of two to halve by while decoding and still have at least [target]. */
    fun subsampling(image: PixelSize, target: PixelSize): Int {
        var sub = 1
        while (image.width / (sub * 2) >= target.width && image.height / (sub * 2) >= target.height) sub *= 2
        return sub
    }

    private const val MIN_EDGE = 16.0
}
