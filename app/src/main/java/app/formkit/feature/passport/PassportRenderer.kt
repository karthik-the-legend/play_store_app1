package app.formkit.feature.passport

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import app.formkit.core.imaging.AndroidImageCodec
import app.formkit.core.imaging.PixelSize
import app.formkit.core.imaging.passport.DirtyRect
import app.formkit.core.imaging.passport.ForegroundMask
import app.formkit.core.imaging.passport.Placement
import app.formkit.core.imaging.passport.SheetLayout
import java.io.File
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class PassportRenderer @Inject constructor(
    private val codec: AndroidImageCodec,
) {

    /** The photo with the mask as its alpha channel. Mutable, so brush strokes can update it in place. */
    fun cutout(photo: Bitmap, mask: ForegroundMask): Bitmap {
        require(photo.width == mask.width && photo.height == mask.height) { "Mask and photo sizes differ" }
        val cutout = Bitmap.createBitmap(photo.width, photo.height, Bitmap.Config.ARGB_8888)
        updateCutout(cutout, photo, mask, DirtyRect(0, 0, photo.width, photo.height))
        return cutout
    }

    /** Copies the mask's alpha into [cutout] within [dirty], after a brush stroke. */
    fun updateCutout(cutout: Bitmap, photo: Bitmap, mask: ForegroundMask, dirty: DirtyRect) {
        if (dirty.isEmpty) return
        val left = max(0, dirty.left)
        val top = max(0, dirty.top)
        val right = min(photo.width, dirty.right)
        val bottom = min(photo.height, dirty.bottom)
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return
        val pixels = IntArray(width * height)
        photo.getPixels(pixels, 0, width, left, top, width, height)
        for (y in 0 until height) {
            val maskRow = (top + y) * mask.width
            for (x in 0 until width) {
                val alpha = mask.alpha[maskRow + left + x].toInt() and 0xFF
                val i = y * width + x
                pixels[i] = (pixels[i] and 0x00FFFFFF) or (alpha shl 24)
            }
        }
        cutout.setPixels(pixels, 0, width, left, top, width, height)
    }

    /** The finished photo: the background colour with the cut-out person placed on it. */
    fun render(cutout: Bitmap, placement: Placement, frame: PixelSize, background: Int): Bitmap {
        val scale = placement.scaleFor(frame)
        // Halve first when the photo is far bigger than the frame: one bilinear pass over a big
        // reduction skips pixels and makes hair and edges shimmer.
        var reduction = 1
        while (scale * reduction * 2 <= 1f && cutout.width / (reduction * 2) >= 1 && cutout.height / (reduction * 2) >= 1) {
            reduction *= 2
        }
        val prepared = if (reduction == 1) {
            cutout
        } else {
            codec.scale(cutout, PixelSize(max(1, cutout.width / reduction), max(1, cutout.height / reduction)))
        }
        try {
            val output = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
            val matrix = Matrix().apply {
                setScale(cutout.width.toFloat() / prepared.width, cutout.height.toFloat() / prepared.height)
                postTranslate(-placement.centerX, -placement.centerY)
                postScale(scale, scale)
                postTranslate(frame.width / 2f, frame.height / 2f)
            }
            Canvas(output).apply {
                drawColor(background)
                drawBitmap(prepared, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            }
            return output
        } finally {
            if (prepared !== cutout) prepared.recycle()
        }
    }

    /**
     * Copies of [photo] at their exact pixel size on a white sheet, each outlined with a faint cut
     * line. [watermark] goes in the bottom margin, never over a photo, and only if there is a margin.
     */
    fun renderSheet(photo: Bitmap, layout: SheetLayout, watermark: String?): Bitmap {
        val sheet = Bitmap.createBitmap(layout.sheet.width, layout.sheet.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.WHITE)
        val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        val cutLine = Paint().apply {
            color = CUT_LINE_COLOR
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        for (slot in layout.slots) {
            canvas.drawBitmap(photo, null, Rect(slot.left, slot.top, slot.left + slot.width, slot.top + slot.height), photoPaint)
            canvas.drawRect(slot.left - 0.5f, slot.top - 0.5f, slot.left + slot.width + 0.5f, slot.top + slot.height + 0.5f, cutLine)
        }
        if (watermark != null && layout.margin > 0) {
            val bottomSpace = layout.sheet.height - layout.slots.maxOf { it.top + it.height }
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = WATERMARK_COLOR
                textSize = min(bottomSpace * 0.45f, MAX_WATERMARK_TEXT_PX)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(watermark, layout.sheet.width / 2f, layout.sheet.height - bottomSpace / 2f + text.textSize / 3f, text)
        }
        return sheet
    }

    /** A one-page PDF of [sheet] at its true physical size, which printers honour more reliably than a JPEG's DPI tag. */
    fun writeSheetPdf(sheet: Bitmap, dpi: Int, output: File) {
        val pointsPerPixel = POINTS_PER_INCH / dpi
        val document = PdfDocument()
        try {
            val pageInfo = PdfDocument.PageInfo.Builder(
                (sheet.width * pointsPerPixel).roundToInt(),
                (sheet.height * pointsPerPixel).roundToInt(),
                1,
            ).create()
            val page = document.startPage(pageInfo)
            page.canvas.drawBitmap(sheet, Matrix().apply { setScale(pointsPerPixel, pointsPerPixel) }, Paint(Paint.FILTER_BITMAP_FLAG))
            document.finishPage(page)
            output.parentFile?.mkdirs()
            output.outputStream().use { document.writeTo(it) }
        } finally {
            document.close()
        }
    }

    private companion object {
        const val CUT_LINE_COLOR = 0xFFC8C8CC.toInt()
        const val WATERMARK_COLOR = 0xFFB4B4B8.toInt()
        const val MAX_WATERMARK_TEXT_PX = 22f
        const val POINTS_PER_INCH = 72f
    }
}
