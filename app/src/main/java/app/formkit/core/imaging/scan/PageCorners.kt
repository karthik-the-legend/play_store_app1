package app.formkit.core.imaging.scan

import app.formkit.core.imaging.PixelSize
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A point on a photo as a fraction of its width and height; (0, 0) is the top-left corner. */
@Serializable
data class PagePoint(val x: Float, val y: Float)

/**
 * Where a page's corners are on a photo, as fractions of the photo's width and height, clockwise
 * from the corner that becomes the top left of the straightened page. Fractions rather than pixels
 * so the same corners work on the small preview and the full-size photo.
 */
@Serializable
data class PageCorners(
    val topLeft: PagePoint,
    val topRight: PagePoint,
    val bottomRight: PagePoint,
    val bottomLeft: PagePoint,
) {
    val points: List<PagePoint> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    /** Moves corner [index] (clockwise from the top left) to [to], kept on the photo. */
    fun moved(index: Int, to: PagePoint): PageCorners {
        val point = PagePoint(to.x.coerceIn(0f, 1f), to.y.coerceIn(0f, 1f))
        return when (index) {
            0 -> copy(topLeft = point)
            1 -> copy(topRight = point)
            2 -> copy(bottomRight = point)
            3 -> copy(bottomLeft = point)
            else -> throw IndexOutOfBoundsException("A page has four corners, not ${index + 1}")
        }
    }

    /** The same outline read a quarter turn clockwise, so the straightened page turns with it. */
    fun rotatedClockwise(): PageCorners = PageCorners(bottomLeft, topLeft, topRight, bottomRight)

    /** Corner positions in pixels on a photo of [photo]'s size: x0, y0, … x3, y3. */
    fun toPixels(photo: PixelSize): FloatArray {
        val corners = points
        return FloatArray(8) { i ->
            val point = corners[i / 2]
            if (i % 2 == 0) point.x * photo.width else point.y * photo.height
        }
    }

    /**
     * Whether a page can be straightened from these corners: they go clockwise, the shape is
     * convex (no side folds back or crosses another), and it covers a real part of the photo.
     */
    fun isUsable(photo: PixelSize): Boolean {
        val p = toPixels(photo)
        for (i in 0 until 4) {
            val a = i
            val b = (i + 1) % 4
            val c = (i + 2) % 4
            val cross = (p[2 * b] - p[2 * a]) * (p[2 * c + 1] - p[2 * b + 1]) - (p[2 * b + 1] - p[2 * a + 1]) * (p[2 * c] - p[2 * b])
            if (cross <= 0f) return false
        }
        return polygonArea(p) >= photo.width.toDouble() * photo.height * MIN_AREA_FRACTION
    }

    /**
     * The straightened page's size in pixels: the longer of each pair of opposite sides, which
     * undoes most of the shrinking that perspective causes, scaled down to at most [maxLongEdge].
     */
    fun outputSize(photo: PixelSize, maxLongEdge: Int): PixelSize {
        val p = toPixels(photo)
        fun side(a: Int, b: Int) = hypot((p[2 * b] - p[2 * a]).toDouble(), (p[2 * b + 1] - p[2 * a + 1]).toDouble())
        val width = max(side(0, 1), side(3, 2))
        val height = max(side(0, 3), side(1, 2))
        val scale = min(1.0, maxLongEdge / max(max(width, height), 1.0))
        return PixelSize(max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()))
    }

    companion object {
        val WholePhoto = PageCorners(PagePoint(0f, 0f), PagePoint(1f, 0f), PagePoint(1f, 1f), PagePoint(0f, 1f))

        /** Smaller than this share of the photo is a slip of the finger, not a page. */
        const val MIN_AREA_FRACTION = 0.02

        /** Four corners in pixels, in any order, arranged clockwise from the one nearest the photo's top left. */
        fun fromPixels(xs: DoubleArray, ys: DoubleArray, photo: PixelSize): PageCorners {
            require(xs.size == 4 && ys.size == 4) { "A page has four corners" }
            val centreX = xs.average()
            val centreY = ys.average()
            // With y pointing down, increasing angle around the centre runs clockwise on screen.
            val order = (0 until 4).sortedBy { atan2(ys[it] - centreY, xs[it] - centreX) }
            val start = (0 until 4).minBy { xs[order[it]] + ys[order[it]] }
            val points = (0 until 4).map { step ->
                val i = order[(start + step) % 4]
                PagePoint((xs[i] / photo.width).toFloat().coerceIn(0f, 1f), (ys[i] / photo.height).toFloat().coerceIn(0f, 1f))
            }
            return PageCorners(points[0], points[1], points[2], points[3])
        }
    }
}

/** The area inside four corners given as x0, y0, … x3, y3 (the shoelace formula). */
internal fun polygonArea(p: FloatArray): Double {
    var twice = 0.0
    for (i in 0 until 4) {
        val j = (i + 1) % 4
        twice += p[2 * i].toDouble() * p[2 * j + 1] - p[2 * j].toDouble() * p[2 * i + 1]
    }
    return abs(twice) / 2
}
