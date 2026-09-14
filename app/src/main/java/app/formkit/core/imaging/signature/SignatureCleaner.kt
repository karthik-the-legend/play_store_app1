package app.formkit.core.imaging.signature

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 8-bit brightness values, row by row. */
class GrayImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height) { "Pixel count doesn't match ${width}x$height" }
    }
}

/** How much ink covers each pixel: 0 is bare paper, 255 is solid ink. */
class InkMask(val width: Int, val height: Int, val coverage: ByteArray) {
    init {
        require(width > 0 && height > 0 && coverage.size == width * height) { "Coverage size doesn't match ${width}x$height" }
    }

    fun at(x: Int, y: Int): Int = coverage[y * width + x].toInt() and 0xFF

    companion object {
        /** Ink coverage from the alpha channel of ARGB pixels, e.g. strokes drawn on a transparent canvas. */
        fun fromAlpha(width: Int, height: Int, argb: IntArray): InkMask =
            InkMask(width, height, ByteArray(argb.size) { (argb[it] ushr 24).toByte() })
    }
}

/** A pixel rectangle. [right] and [bottom] are exclusive. */
data class InkBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        require(right > left && bottom > top) { "Empty bounds: $left,$top – $right,$bottom" }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Turns a photo of a signature into clean ink on nothing.
 *
 * Each pixel is compared with the average brightness of the paper around it (an adaptive
 * threshold using an integral image). Shadows, uneven light and off-white paper change the
 * paper and the ink around them by the same proportion, so they drop out; a pen stroke is much
 * darker than its surroundings and stays. Edges keep a soft ramp instead of a hard cut, so
 * strokes don't come out jagged.
 *
 * Whatever is left and clearly isn't handwriting is then removed: specks, ruled lines that run
 * across the page, and the narrow bands a hard shadow or the paper's edge leaves when they
 * cross the whole photo.
 */
object SignatureCleaner {

    fun integralOf(image: GrayImage): LongArray {
        val width = image.width
        val stride = width + 1
        val sums = LongArray(stride * (image.height + 1))
        for (y in 0 until image.height) {
            var rowSum = 0L
            for (x in 0 until width) {
                rowSum += image.pixels[y * width + x]
                sums[(y + 1) * stride + x + 1] = sums[y * stride + x + 1] + rowSum
            }
        }
        return sums
    }

    /** [inkStrength] runs from 0 (only the darkest strokes) to 1 (faint strokes, and thickened). */
    fun clean(image: GrayImage, integral: LongArray, inkStrength: Float): InkMask {
        val width = image.width
        val height = image.height
        val stride = width + 1
        require(integral.size == stride * (height + 1)) { "Integral image doesn't match the image" }

        val radius = windowRadius(width, height)
        val strength = inkStrength.coerceIn(0f, 1f)
        val threshold = 1f - lerp(WEAKEST_SENSITIVITY, STRONGEST_SENSITIVITY, strength)
        val coverage = ByteArray(width * height)

        for (y in 0 until height) {
            val top = max(0, y - radius)
            val bottom = min(height, y + radius + 1)
            for (x in 0 until width) {
                val left = max(0, x - radius)
                val right = min(width, x + radius + 1)
                val sum = integral[bottom * stride + right] - integral[top * stride + right] -
                    integral[bottom * stride + left] + integral[top * stride + left]
                val mean = max(sum.toFloat() / ((right - left) * (bottom - top)), 1f)
                val ratio = image.pixels[y * width + x] / mean
                val ink = ((threshold - ratio) / SOFTNESS).coerceIn(0f, 1f)
                coverage[y * width + x] = (ink * 255f + 0.5f).toInt().toByte()
            }
        }

        val mask = InkMask(width, height, coverage)
        removeNonInk(mask, radius)
        return if (strength >= THICKEN_FROM_STRENGTH) thicken(mask) else mask
    }

    /** The box around all solid-enough ink, plus a margin; null if there is no ink. */
    fun findInkBounds(mask: InkMask, marginFraction: Float = MARGIN_FRACTION): InkBounds? {
        var minX = mask.width
        var minY = mask.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until mask.height) {
            for (x in 0 until mask.width) {
                if (mask.at(x, y) >= BOUNDS_MIN_COVERAGE) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return null
        val margin = max(MIN_MARGIN_PX, (max(maxX - minX + 1, maxY - minY + 1) * marginFraction).roundToInt())
        return InkBounds(
            left = max(0, minX - margin),
            top = max(0, minY - margin),
            right = min(mask.width, maxX + 1 + margin),
            bottom = min(mask.height, maxY + 1 + margin),
        )
    }

    private fun removeNonInk(mask: InkMask, radius: Int) {
        val width = mask.width
        val height = mask.height
        val coverage = mask.coverage
        val visited = BooleanArray(width * height)
        val stack = IntArray(width * height)
        val members = IntArray(width * height)
        val minArea = max(MIN_SPECK_AREA, (width.toLong() * height / SPECK_AREA_DIVISOR).toInt())

        for (start in 0 until width * height) {
            if (visited[start] || coverage[start].toInt() == 0) continue
            var stackSize = 0
            var memberCount = 0
            stack[stackSize++] = start
            visited[start] = true
            var minX = width
            var minY = height
            var maxX = -1
            var maxY = -1

            while (stackSize > 0) {
                val index = stack[--stackSize]
                members[memberCount++] = index
                val x = index % width
                val y = index / width
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= height) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= width || (dx == 0 && dy == 0)) continue
                        val neighbour = ny * width + nx
                        if (!visited[neighbour] && coverage[neighbour].toInt() != 0) {
                            visited[neighbour] = true
                            stack[stackSize++] = neighbour
                        }
                    }
                }
            }

            val component = Component(memberCount, minX, minY, maxX, maxY)
            if (component.isNotInk(width, height, minArea, radius)) {
                for (i in 0 until memberCount) coverage[members[i]] = 0
            }
        }
    }

    private class Component(val area: Int, val minX: Int, val minY: Int, val maxX: Int, val maxY: Int) {
        val boxWidth get() = maxX - minX + 1
        val boxHeight get() = maxY - minY + 1

        fun isNotInk(imageWidth: Int, imageHeight: Int, minArea: Int, radius: Int): Boolean {
            if (area < minArea) return true

            // Ruled lines: long and thin, running most of the way across or down the page.
            val horizontalRule = boxWidth >= imageWidth * RULE_SPAN && boxHeight <= max(3f, imageHeight * RULE_THICKNESS)
            val verticalRule = boxHeight >= imageHeight * RULE_SPAN && boxWidth <= max(3f, imageWidth * RULE_THICKNESS)
            if (horizontalRule || verticalRule) return true

            // A hard shadow or the paper's edge crossing the whole photo leaves a band about one
            // window wide. A signature touching that band makes the component much wider, so it's kept.
            val spansTopToBottom = minY == 0 && maxY == imageHeight - 1 && boxWidth <= radius * EDGE_BAND_WINDOWS
            val spansSideToSide = minX == 0 && maxX == imageWidth - 1 && boxHeight <= radius * EDGE_BAND_WINDOWS
            if (spansTopToBottom || spansSideToSide) return true

            // Diagonal edges from the border: large but mostly empty inside their box.
            val touchesBorder = minX == 0 || minY == 0 || maxX == imageWidth - 1 || maxY == imageHeight - 1
            val large = boxWidth >= imageWidth * EDGE_SPAN || boxHeight >= imageHeight * EDGE_SPAN
            val fill = area.toFloat() / (boxWidth.toLong() * boxHeight)
            return touchesBorder && large && fill < EDGE_FILL
        }
    }

    /** One pass of 3×3 dilation: every stroke grows by a pixel on each side. */
    private fun thicken(mask: InkMask): InkMask {
        val width = mask.width
        val height = mask.height
        val source = mask.coverage
        val result = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var strongest = 0
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= height) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= width) continue
                        val value = source[ny * width + nx].toInt() and 0xFF
                        if (value > strongest) strongest = value
                    }
                }
                result[y * width + x] = strongest.toByte()
            }
        }
        return InkMask(width, height, result)
    }

    internal fun windowRadius(width: Int, height: Int): Int = max(MIN_WINDOW_RADIUS, max(width, height) / WINDOW_DIVISOR)

    private fun lerp(from: Float, to: Float, fraction: Float) = from + (to - from) * fraction

    private const val MIN_WINDOW_RADIUS = 8
    private const val WINDOW_DIVISOR = 24

    /** How much darker than the surrounding paper a pixel must be to count, at strength 0 and 1. */
    private const val WEAKEST_SENSITIVITY = 0.35f
    private const val STRONGEST_SENSITIVITY = 0.08f

    /** Width of the soft edge, as a brightness ratio. */
    private const val SOFTNESS = 0.1f
    private const val THICKEN_FROM_STRENGTH = 0.75f

    private const val MIN_SPECK_AREA = 6
    private const val SPECK_AREA_DIVISOR = 100_000L
    private const val RULE_SPAN = 0.8f
    private const val RULE_THICKNESS = 0.03f
    private const val EDGE_BAND_WINDOWS = 3
    private const val EDGE_SPAN = 0.6f
    private const val EDGE_FILL = 0.3f

    private const val BOUNDS_MIN_COVERAGE = 64
    private const val MARGIN_FRACTION = 0.08f
    private const val MIN_MARGIN_PX = 6
}

object SignatureRenderer {

    /**
     * ARGB pixels for [crop] of [mask]. Transparent output is black ink whose alpha is the
     * coverage, so bare paper has alpha 0. White output blends the ink onto opaque white.
     */
    fun toArgb(mask: InkMask, crop: InkBounds, transparent: Boolean): IntArray {
        require(crop.right <= mask.width && crop.bottom <= mask.height) { "Crop $crop is outside the ${mask.width}x${mask.height} mask" }
        val pixels = IntArray(crop.width * crop.height)
        var i = 0
        for (y in crop.top until crop.bottom) {
            for (x in crop.left until crop.right) {
                val coverage = mask.at(x, y)
                pixels[i++] = if (transparent) {
                    coverage shl 24
                } else {
                    val shade = 255 - coverage
                    (0xFF shl 24) or (shade shl 16) or (shade shl 8) or shade
                }
            }
        }
        return pixels
    }
}
