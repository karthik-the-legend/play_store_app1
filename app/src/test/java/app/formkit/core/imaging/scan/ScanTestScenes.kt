package app.formkit.core.imaging.scan

import app.formkit.core.imaging.signature.GrayImage
import kotlin.math.abs
import kotlin.random.Random

/** A projective transform between two four-point shapes, used to draw pages seen at an angle. */
internal class Homography private constructor(private val m: DoubleArray) {

    fun map(x: Double, y: Double): Pair<Double, Double> {
        val w = m[6] * x + m[7] * y + m[8]
        return (m[0] * x + m[1] * y + m[2]) / w to (m[3] * x + m[4] * y + m[5]) / w
    }

    companion object {
        /** Maps [from] (x0, y0, … x3, y3) onto [to]. */
        fun between(from: DoubleArray, to: DoubleArray): Homography {
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val x = from[2 * i]
                val y = from[2 * i + 1]
                val u = to[2 * i]
                val v = to[2 * i + 1]
                a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
            }
            for (column in 0 until 8) {
                val pivot = (column until 8).maxBy { abs(a[it][column]) }
                require(abs(a[pivot][column]) > 1e-12) { "The points don't make a quadrilateral" }
                a[column] = a[pivot].also { a[pivot] = a[column] }
                for (row in 0 until 8) {
                    if (row == column) continue
                    val factor = a[row][column] / a[column][column]
                    for (c in column until 9) a[row][c] -= factor * a[column][c]
                }
            }
            return Homography(DoubleArray(9) { if (it == 8) 1.0 else a[it][8] / a[it][it] })
        }
    }
}

/** Draws a greyscale photo of a page lying on a table. */
internal class PhotoScene(val width: Int = 480, val height: Int = 360, seed: Int = 11) {
    private val random = Random(seed)
    val pixels = IntArray(width * height)

    fun table(shade: Int) = apply { pixels.fill(shade) }

    /** Rectangles of random sizes and shades: keys, papers, a patterned cloth. */
    fun clutter(count: Int) = apply {
        repeat(count) {
            val w = random.nextInt(10, 70)
            val h = random.nextInt(10, 70)
            val left = random.nextInt(-20, width)
            val top = random.nextInt(-20, height)
            val shade = random.nextInt(30, 210)
            for (y in maxOf(0, top) until minOf(height, top + h)) {
                for (x in maxOf(0, left) until minOf(width, left + w)) pixels[y * width + x] = shade
            }
        }
    }

    /** A page whose corners land at [corners] (clockwise from top left), with lines of text and an optional printed box. */
    fun page(corners: List<Pair<Double, Double>>, paper: Int = 228, text: Boolean = true, box: Boolean = false) = apply {
        val unit = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0)
        val toPage = Homography.between(corners.flatMap { listOf(it.first, it.second) }.toDoubleArray(), unit)
        val ink = (paper * 0.4).toInt()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val (u, v) = toPage.map(x + 0.5, y + 0.5)
                if (u < 0 || u > 1 || v < 0 || v > 1) continue
                val onBox = box && u in 0.2..0.8 && v in 0.3..0.7 &&
                    (abs(u - 0.2) < 0.006 || abs(u - 0.8) < 0.006 || abs(v - 0.3) < 0.006 || abs(v - 0.7) < 0.006)
                val onText = text && u in 0.12..0.88 && v in 0.1..0.9 && (v * 50).toInt() % 2 == 0 && (u * 40).toInt() % 5 != 4
                pixels[y * width + x] = if (onBox || onText) ink else paper
            }
        }
    }

    /** A filled ellipse, such as a thumb holding the page down. */
    fun blob(centreX: Int, centreY: Int, radiusX: Int, radiusY: Int, shade: Int) = apply {
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = (x - centreX).toDouble() / radiusX
                val dy = (y - centreY).toDouble() / radiusY
                if (dx * dx + dy * dy <= 1.0) pixels[y * width + x] = shade
            }
        }
    }

    fun noise(amount: Int) = apply {
        for (i in pixels.indices) pixels[i] = (pixels[i] + random.nextInt(-amount, amount + 1)).coerceIn(0, 255)
    }

    fun gray() = GrayImage(width, height, pixels.copyOf())
}
