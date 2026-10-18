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
import app.formkit.core.imaging.passport.EdgeColors
import app.formkit.core.imaging.passport.ForegroundMask
import app.formkit.core.imaging.passport.Placement
import app.formkit.core.imaging.passport.SheetLayout
import java.io.File
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.math.floor
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

    /**
     * Copies the mask's alpha into [cutout] within [dirty], after a brush stroke, and takes the old
     * background's colour out of the soft edge (see [EdgeColors]). Large areas go in tiles, so the
     * whole photo never needs more than a few megabytes of working memory.
     */
    fun updateCutout(cutout: Bitmap, photo: Bitmap, mask: ForegroundMask, dirty: DirtyRect) {
        if (dirty.isEmpty) return
        val left = max(0, dirty.left)
        val top = max(0, dirty.top)
        val right = min(photo.width, dirty.right)
        val bottom = min(photo.height, dirty.bottom)
        var tileTop = top
        while (tileTop < bottom) {
            val tileBottom = min(bottom, tileTop + TILE)
            var tileLeft = left
            while (tileLeft < right) {
                val tileRight = min(right, tileLeft + TILE)
                updateTile(cutout, photo, mask, tileLeft, tileTop, tileRight, tileBottom)
                tileLeft = tileRight
            }
            tileTop = tileBottom
        }
    }

    private fun updateTile(cutout: Bitmap, photo: Bitmap, mask: ForegroundMask, left: Int, top: Int, right: Int, bottom: Int) {
        // The edge colours look at neighbouring pixels, so read a margin around the tile.
        val margin = EdgeColors.RADIUS
        val readLeft = max(0, left - margin)
        val readTop = max(0, top - margin)
        val width = min(photo.width, right + margin) - readLeft
        val height = min(photo.height, bottom + margin) - readTop
        val pixels = IntArray(width * height)
        photo.getPixels(pixels, 0, width, readLeft, readTop, width, height)
        for (y in 0 until height) {
            val maskRow = (readTop + y) * mask.width
            for (x in 0 until width) {
                val alpha = mask.alpha[maskRow + readLeft + x].toInt() and 0xFF
                val i = y * width + x
                pixels[i] = (pixels[i] and 0x00FFFFFF) or (alpha shl 24)
            }
        }
        EdgeColors.decontaminate(pixels, width, height, left - readLeft, top - readTop, right - readLeft, bottom - readTop)
        cutout.setPixels(pixels, (top - readTop) * width + (left - readLeft), width, left, top, right - left, bottom - top)
    }

    /** The finished photo: the background colour with the cut-out person placed on it. */
    fun render(cutout: Bitmap, placement: Placement, frame: PixelSize, background: Int): Bitmap {
        val scale = placement.scaleFor(frame)
        val output = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(background)

        // Only the part of the photo the frame shows, plus enough around it for the filter, gets
        // scaled: zoomed in a long way, the whole photo at that scale wouldn't fit in memory.
        val reduction = 1f / scale
        val margin = ceil(FILTER_REACH * max(1f, reduction)).toInt() + 2
        val (visibleLeft, visibleTop) = placement.toSource(0f, 0f, frame)
        val (visibleRight, visibleBottom) = placement.toSource(frame.width.toFloat(), frame.height.toFloat(), frame)
        val left = max(0, floor(visibleLeft).toInt() - margin)
        val top = max(0, floor(visibleTop).toInt() - margin)
        val right = min(cutout.width, ceil(visibleRight).toInt() + margin)
        val bottom = min(cutout.height, ceil(visibleBottom).toInt() + margin)
        if (right > left && bottom > top) {
            val region = if (left == 0 && top == 0 && right == cutout.width && bottom == cutout.height) {
                cutout
            } else {
                Bitmap.createBitmap(cutout, left, top, right - left, bottom - top)
            }
            val scaledSize = PixelSize(max(1, ((right - left) * scale).roundToInt()), max(1, ((bottom - top) * scale).roundToInt()))
            // One high-quality resize, then placed on whole pixels so nothing filters it again.
            val scaled = codec.scale(region, scaledSize)
            if (region !== cutout && region !== scaled) region.recycle()
            try {
                val (x, y) = placement.toFrame(left.toFloat(), top.toFloat(), frame)
                canvas.drawBitmap(scaled, x.roundToInt().toFloat(), y.roundToInt().toFloat(), null)
            } finally {
                scaled.recycle()
            }
        }
        // Shrinking softens a sharp photo; a light unsharp mask brings back the crispness.
        if (reduction >= SHARPEN_FROM_REDUCTION) codec.sharpenInPlace(output)
        return output
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
        const val TILE = 512

        /** How far, in source pixels per unit of reduction, the resize filter reaches (Lanczos-3). */
        const val FILTER_REACH = 3f

        /** Shrinking by less than this leaves the photo crisp enough not to need sharpening. */
        const val SHARPEN_FROM_REDUCTION = 1.25f
    }
}
