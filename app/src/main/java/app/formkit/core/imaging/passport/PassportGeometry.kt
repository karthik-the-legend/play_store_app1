package app.formkit.core.imaging.passport

import app.formkit.core.imaging.PixelSize
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

const val DEFAULT_DPI = 300
private const val MM_PER_INCH = 25.4f

fun millimetresToPixels(millimetres: Float, dpi: Int): Int = (millimetres / MM_PER_INCH * dpi).roundToInt()

/**
 * Where a head should sit, as fractions of the photo's height measured from the top. These are
 * starting guides drawn over the photo, not a certification: forms publish their own rules.
 */
data class HeadGuide(
    /** Top of the head, including hair. */
    val crown: Float,
    /** Bottom of the chin. */
    val chin: Float,
    /** The line through the eyes. */
    val eyes: Float,
) {
    val headHeight: Float get() = chin - crown
}

/** ICAO 35×45 mm: head 32–36 mm tall, a few millimetres of space above it. */
val Icao35x45Guide = HeadGuide(crown = 4f / 45f, chin = 38f / 45f, eyes = 20f / 45f)

/** US 2×2 in: head 1–1⅜ in tall, eyes 1⅛–1⅜ in above the bottom edge. */
val Square2x2Guide = HeadGuide(crown = 0.096f, chin = 0.69f, eyes = 0.375f)

enum class PassportPreset(val widthMm: Float, val heightMm: Float, val guide: HeadGuide) {
    India(35f, 45f, Icao35x45Guide),
    UnitedStates(50.8f, 50.8f, Square2x2Guide),
    Square51(51f, 51f, Square2x2Guide),
    Schengen(35f, 45f, Icao35x45Guide),
    ;

    fun pixelSize(dpi: Int = DEFAULT_DPI) = PixelSize(millimetresToPixels(widthMm, dpi), millimetresToPixels(heightMm, dpi))
}

/**
 * Where the source photo sits behind the output frame, in source pixels: the point shown at the
 * frame's centre and how much of the source's height fills the frame. Keeping it independent of
 * the output size means switching presets keeps the head where it was.
 */
@Serializable
data class Placement(val centerX: Float, val centerY: Float, val visibleHeight: Float) {
    init {
        require(visibleHeight > 0f) { "visibleHeight must be positive" }
    }

    /** Output pixels per source pixel for a frame of [frame] size. */
    fun scaleFor(frame: PixelSize): Float = frame.height / visibleHeight

    /** Where source pixel ([sourceX], [sourceY]) lands in the frame. */
    fun toFrame(sourceX: Float, sourceY: Float, frame: PixelSize): Pair<Float, Float> {
        val scale = scaleFor(frame)
        return Pair(frame.width / 2f + (sourceX - centerX) * scale, frame.height / 2f + (sourceY - centerY) * scale)
    }

    /** The source pixel under frame point ([frameX], [frameY]). */
    fun toSource(frameX: Float, frameY: Float, frame: PixelSize): Pair<Float, Float> {
        val scale = scaleFor(frame)
        return Pair(centerX + (frameX - frame.width / 2f) / scale, centerY + (frameY - frame.height / 2f) / scale)
    }

    /** Moves the photo by a drag of ([dx], [dy]) frame pixels. */
    fun dragged(dx: Float, dy: Float, frame: PixelSize): Placement {
        val scale = scaleFor(frame)
        return copy(centerX = centerX - dx / scale, centerY = centerY - dy / scale)
    }

    /** Zooms by [factor] (above 1 zooms in) keeping frame point ([focusX], [focusY]) fixed. */
    fun zoomed(factor: Float, focusX: Float, focusY: Float, frame: PixelSize): Placement {
        val (sourceX, sourceY) = toSource(focusX, focusY, frame)
        val newVisible = (visibleHeight / factor).coerceIn(MIN_VISIBLE_HEIGHT, MAX_ZOOM_OUT * visibleHeight.coerceAtLeast(1f))
        val newScale = frame.height / newVisible
        return Placement(
            centerX = sourceX - (focusX - frame.width / 2f) / newScale,
            centerY = sourceY - (focusY - frame.height / 2f) / newScale,
            visibleHeight = newVisible,
        )
    }

    companion object {
        private const val MIN_VISIBLE_HEIGHT = 16f
        private const val MAX_ZOOM_OUT = 8f

        /** The whole source, filling the frame and centred. */
        fun cover(source: PixelSize, frame: PixelSize): Placement {
            val frameAspect = frame.width.toFloat() / frame.height
            val sourceAspect = source.width.toFloat() / source.height
            val visibleHeight = if (sourceAspect > frameAspect) source.height.toFloat() else source.width / frameAspect
            return Placement(source.width / 2f, source.height / 2f, visibleHeight)
        }
    }
}

/** Crown, chin and horizontal centre of a head, in source pixels. */
data class HeadMeasurement(val crownY: Float, val chinY: Float, val centerX: Float) {
    val height: Float get() = chinY - crownY
}

object HeadFraming {

    /**
     * Estimates the head from a person silhouette. The top of the mask is the crown; the width
     * of the upper head gives the head's likely height; and if the silhouette narrows into a
     * neck where the chin is expected, that row is taken as the chin. It's a starting position:
     * the user drags and zooms from there.
     */
    fun measureHead(mask: ForegroundMask): HeadMeasurement? {
        val width = mask.width
        val height = mask.height
        val rowWidths = IntArray(height)
        val rowLeft = IntArray(height) { width }
        val rowRight = IntArray(height) { -1 }
        for (y in 0 until height) {
            var count = 0
            for (x in 0 until width) {
                if (mask.at(x, y) >= FOREGROUND) {
                    count++
                    if (x < rowLeft[y]) rowLeft[y] = x
                    if (x > rowRight[y]) rowRight[y] = x
                }
            }
            rowWidths[y] = count
        }

        val minRowWidth = max(3, width / 100)
        val crown = (0 until height).firstOrNull { rowWidths[it] >= minRowWidth } ?: return null

        // The upper head: widest row within a tenth of the image below the crown.
        val upperEnd = min(height - 1, crown + max(4, height / 10))
        val upperHeadWidth = (crown..upperEnd).maxOf { rowWidths[it] }
        val headWidth = upperHeadWidth / UPPER_HEAD_WIDTH_FRACTION
        var headHeight = headWidth * HEAD_HEIGHT_PER_WIDTH

        // Prefer a visible neck: the narrowest row around where the chin is expected. Seen from the
        // front, the chin sits in front of the neck, so the silhouette is narrowest where the jaw
        // meets the neck, a little above the chin itself.
        val searchStart = (crown + headHeight * NECK_SEARCH_FROM).toInt().coerceIn(crown, height - 1)
        val searchEnd = (crown + headHeight * NECK_SEARCH_TO).toInt().coerceIn(searchStart, height - 1)
        val neck = (searchStart..searchEnd).minByOrNull { rowWidths[it] }
        if (neck != null && rowWidths[neck] > 0 && rowWidths[neck] < headWidth * NECK_WIDTH_FRACTION) {
            headHeight = (neck - crown) * (1f + JAW_TO_CHIN)
        }

        val middleRow = (crown + headHeight * 0.4f).toInt().coerceIn(0, height - 1)
        val centerX = if (rowRight[middleRow] >= 0) (rowLeft[middleRow] + rowRight[middleRow]) / 2f else width / 2f
        return HeadMeasurement(crownY = crown.toFloat(), chinY = crown + headHeight, centerX = centerX)
    }

    /** A placement that puts [head]'s crown and chin on [guide]'s lines. */
    fun placementFor(head: HeadMeasurement, guide: HeadGuide): Placement {
        val visibleHeight = head.height / guide.headHeight
        val centerY = head.crownY + (0.5f - guide.crown) * visibleHeight
        return Placement(head.centerX, centerY, visibleHeight)
    }

    private const val FOREGROUND = 128
    private const val UPPER_HEAD_WIDTH_FRACTION = 0.9f
    private const val HEAD_HEIGHT_PER_WIDTH = 1.3f
    private const val NECK_SEARCH_FROM = 0.8f
    private const val NECK_SEARCH_TO = 1.35f
    private const val NECK_WIDTH_FRACTION = 0.8f
    /** How far below the jaw line the chin is, as a fraction of the crown-to-jaw height. */
    private const val JAW_TO_CHIN = 0.12f
}
