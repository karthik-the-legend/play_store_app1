package app.formkit.feature.signature

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import app.formkit.core.di.DefaultDispatcher
import app.formkit.core.imaging.AndroidImageCodec
import app.formkit.core.imaging.OutputFormat
import app.formkit.core.imaging.PixelSize
import app.formkit.core.imaging.SizeTarget
import app.formkit.core.imaging.SizeTargeter
import app.formkit.core.imaging.SourceImageDecoder
import app.formkit.core.imaging.TargetOutcome
import app.formkit.core.imaging.signature.GrayImage
import app.formkit.core.imaging.signature.InkBounds
import app.formkit.core.imaging.signature.InkMask
import app.formkit.core.imaging.signature.SignatureCleaner
import app.formkit.core.imaging.signature.SignatureRenderer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A photo decoded and prepared for cleaning; cleaning at another ink strength reuses it. */
class PhotoWork(val gray: GrayImage, val integral: LongArray)

class CleanedSignature(
    val mask: InkMask,
    val inkBounds: InkBounds?,
    /** The whole cleaned photo on white, for choosing the crop. */
    val preview: Bitmap,
)

sealed interface SignatureOutcome {
    data class Done(val file: File, val sizeBytes: Long, val size: PixelSize) : SignatureOutcome
    data class TooLarge(val smallestBytes: Long, val size: PixelSize) : SignatureOutcome
    data object NoInk : SignatureOutcome
}

class SignatureProcessor @Inject constructor(
    private val decoder: SourceImageDecoder,
    private val codec: AndroidImageCodec,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {

    suspend fun loadPhoto(file: File): PhotoWork = withContext(dispatcher) {
        var bitmap = decoder.decode(file, maxLongEdge = WORKING_LONG_EDGE * 2).bitmap
        try {
            val size = PixelSize(bitmap.width, bitmap.height)
            if (size.longEdge > WORKING_LONG_EDGE) {
                val scaled = codec.scale(bitmap, size.scaledBy(WORKING_LONG_EDGE.toDouble() / size.longEdge))
                bitmap.recycle()
                bitmap = scaled
            }
            val gray = grayscale(bitmap)
            PhotoWork(gray, SignatureCleaner.integralOf(gray))
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun clean(work: PhotoWork, inkStrength: Float): CleanedSignature = withContext(dispatcher) {
        val mask = SignatureCleaner.clean(work.gray, work.integral, inkStrength)
        val whole = InkBounds(0, 0, mask.width, mask.height)
        val preview = Bitmap.createBitmap(
            SignatureRenderer.toArgb(mask, whole, transparent = false),
            mask.width,
            mask.height,
            Bitmap.Config.ARGB_8888,
        )
        CleanedSignature(mask, SignatureCleaner.findInkBounds(mask), preview)
    }

    suspend fun exportPhoto(
        work: PhotoWork,
        inkStrength: Float,
        crop: CropRect,
        target: SizeTarget,
        output: File,
    ): SignatureOutcome = withContext(dispatcher) {
        val mask = SignatureCleaner.clean(work.gray, work.integral, inkStrength)
        export(mask, crop.toBounds(mask.width, mask.height), target, output)
    }

    suspend fun exportDrawing(drawing: Drawing, target: SizeTarget, output: File): SignatureOutcome = withContext(dispatcher) {
        val mask = renderDrawing(drawing)
        val bounds = SignatureCleaner.findInkBounds(mask) ?: return@withContext SignatureOutcome.NoInk
        export(mask, bounds, target, output)
    }

    private suspend fun export(mask: InkMask, crop: InkBounds, target: SizeTarget, output: File): SignatureOutcome {
        val transparent = target.format == OutputFormat.Png
        val cropped = Bitmap.createBitmap(
            SignatureRenderer.toArgb(mask, crop, transparent),
            crop.width,
            crop.height,
            Bitmap.Config.ARGB_8888,
        )
        val bitmaps = mutableListOf(cropped)
        try {
            val source = target.exactSize?.let { exact -> fitInside(cropped, exact, transparent).also { bitmaps += it } } ?: cropped
            return when (val outcome = SizeTargeter(codec).fit(source, target)) {
                is TargetOutcome.Fitted -> {
                    output.parentFile?.mkdirs()
                    output.writeBytes(outcome.bytes)
                    val written = output.length()
                    check(written <= target.maxBytes) { "Wrote $written bytes, over the ${target.maxBytes} byte limit" }
                    SignatureOutcome.Done(output, written, outcome.size)
                }
                is TargetOutcome.TooLarge -> SignatureOutcome.TooLarge(outcome.smallestBytes, outcome.size)
                // Signature targets never set a minimum.
                is TargetOutcome.TooSmall -> error("Unexpected minimum-size result")
            }
        } finally {
            bitmaps.forEach { it.recycle() }
        }
    }

    /**
     * Centres the signature in the exact size a form asks for, scaling it to fit and filling the
     * rest with the background. Cropping to the shape instead could cut off part of the name.
     */
    private fun fitInside(source: Bitmap, target: PixelSize, transparent: Boolean): Bitmap {
        val scale = min(target.width.toDouble() / source.width, target.height.toDouble() / source.height)
        val fitted = PixelSize(
            max(1, (source.width * scale).roundToInt()).coerceAtMost(target.width),
            max(1, (source.height * scale).roundToInt()).coerceAtMost(target.height),
        )
        val scaled = if (fitted.width == source.width && fitted.height == source.height) source else codec.scale(source, fitted)
        val canvasBitmap = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
        Canvas(canvasBitmap).apply {
            if (!transparent) drawColor(Color.WHITE)
            drawBitmap(
                scaled,
                ((target.width - fitted.width) / 2).toFloat(),
                ((target.height - fitted.height) / 2).toFloat(),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
        }
        if (scaled !== source) scaled.recycle()
        return canvasBitmap
    }

    internal fun renderDrawing(drawing: Drawing): InkMask {
        val width = DRAWING_RENDER_WIDTH
        val height = (width / Drawing.ASPECT_RATIO).roundToInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            for (stroke in drawing.strokes) {
                if (stroke.points.size < 2) continue
                val strokePx = stroke.width * width
                if (stroke.points.size == 2) {
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(stroke.points[0] * width, stroke.points[1] * height, strokePx / 2, paint)
                } else {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = strokePx
                    canvas.drawPath(strokePath(stroke.points, width.toFloat(), height.toFloat()), paint)
                }
            }
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            return InkMask.fromAlpha(width, height, pixels)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Brightness for cleaning: the average of luminance and the brightest channel. Coloured
     * ruled lines (light blue, red) are bright in at least one channel, so they fade into the
     * paper, while blue and black pen stays dark. Transparent pixels count as white paper.
     */
    private fun grayscale(bitmap: Bitmap): GrayImage {
        val width = bitmap.width
        val height = bitmap.height
        val row = IntArray(width)
        val gray = IntArray(width * height)
        for (y in 0 until height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val color = row[x]
                val alpha = color ushr 24
                val red = (color shr 16) and 0xFF
                val green = (color shr 8) and 0xFF
                val blue = color and 0xFF
                val luminance = (red * 77 + green * 150 + blue * 29) shr 8
                val brightness = (luminance + max(red, max(green, blue))) / 2
                gray[y * width + x] = 255 - (255 - brightness) * alpha / 255
            }
        }
        return GrayImage(width, height, gray)
    }

    companion object {
        /** Plenty for a signature crop; keeps cleaning fast enough to follow the slider. */
        const val WORKING_LONG_EDGE = 1600
        const val DRAWING_RENDER_WIDTH = 1500
    }
}

/** Smooths a stroke by curving through the midpoints between its recorded points. */
internal fun strokePath(points: List<Float>, width: Float, height: Float): Path = Path().apply {
    moveTo(points[0] * width, points[1] * height)
    var i = 2
    while (i + 3 < points.size) {
        val controlX = points[i] * width
        val controlY = points[i + 1] * height
        val nextX = points[i + 2] * width
        val nextY = points[i + 3] * height
        quadTo(controlX, controlY, (controlX + nextX) / 2, (controlY + nextY) / 2)
        i += 2
    }
    lineTo(points[points.size - 2] * width, points[points.size - 1] * height)
}
