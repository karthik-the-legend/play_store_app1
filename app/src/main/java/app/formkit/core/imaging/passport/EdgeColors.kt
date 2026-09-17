package app.formkit.core.imaging.passport

import kotlin.math.max
import kotlin.math.min

/**
 * Takes the old background's colour out of a cut-out's soft edge.
 *
 * A pixel on the outline is part person, part background: behind a dark jacket on a green wall, its
 * colour is dark green. Placed on a white background with half its opacity it still shows as a green
 * rim. The person's own colour at that spot is estimated from the pixels around it, weighted towards
 * the ones that are surely person, and used for the soft pixels instead.
 */
object EdgeColors {

    /**
     * Rewrites the colour of every partly transparent pixel in [argb] (row by row, [width] wide, the
     * alpha already set) inside the rectangle [left]..[right) × [top]..[bottom). Pixels outside the
     * rectangle are read but not changed, so callers can pass a margin of [RADIUS] around it.
     */
    fun decontaminate(
        argb: IntArray,
        width: Int,
        height: Int,
        left: Int = 0,
        top: Int = 0,
        right: Int = width,
        bottom: Int = height,
    ) {
        require(argb.size == width * height) { "Pixel count doesn't match ${width}x$height" }
        if (right <= left || bottom <= top || !hasSoftPixel(argb, width, height, left, top, right, bottom)) return
        val weights = FloatArray(argb.size)
        val red = FloatArray(argb.size)
        val green = FloatArray(argb.size)
        val blue = FloatArray(argb.size)
        for (i in argb.indices) {
            val alpha = argb[i] ushr 24
            // Surely-person pixels count far more than soft ones, which still hold some background.
            val w = (alpha / 255f).let { it * it * it * it }
            weights[i] = w
            red[i] = ((argb[i] shr 16) and 0xFF) * w
            green[i] = ((argb[i] shr 8) and 0xFF) * w
            blue[i] = (argb[i] and 0xFF) * w
        }
        val meanWeight = Grid.boxMean(weights, width, height, RADIUS)
        val meanRed = Grid.boxMean(red, width, height, RADIUS)
        val meanGreen = Grid.boxMean(green, width, height, RADIUS)
        val meanBlue = Grid.boxMean(blue, width, height, RADIUS)
        for (y in max(0, top) until min(height, bottom)) {
            for (x in max(0, left) until min(width, right)) {
                val i = y * width + x
                val alpha = argb[i] ushr 24
                if (alpha == 0 || alpha > SOFT_MAX) continue
                val w = meanWeight[i]
                if (w < MIN_WEIGHT) continue
                val r = (meanRed[i] / w).toInt().coerceIn(0, 255)
                val g = (meanGreen[i] / w).toInt().coerceIn(0, 255)
                val b = (meanBlue[i] / w).toInt().coerceIn(0, 255)
                argb[i] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    /** Most of a photo is plain background or plain person; those areas need no work at all. */
    private fun hasSoftPixel(argb: IntArray, width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int): Boolean {
        for (y in max(0, top) until min(height, bottom)) {
            val row = y * width
            for (x in max(0, left) until min(width, right)) {
                if ((argb[row + x] ushr 24) in 1..SOFT_MAX) return true
            }
        }
        return false
    }

    /** How far to look for the person's own colour. */
    const val RADIUS = 6

    /** Alpha above this is treated as person, colour and all. */
    private const val SOFT_MAX = 250

    /** Below this there's too little person nearby to say what colour it is. */
    private const val MIN_WEIGHT = 0.01f
}
