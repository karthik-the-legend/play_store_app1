package app.formkit.core.imaging

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * High-quality resizing of ARGB pixels (Android's unpremultiplied `0xAARRGGBB`), one row at a time.
 *
 * Android's own scaling is bilinear: four source pixels per output pixel, whatever the reduction.
 * Shrinking by 1.5× that way both blurs (the 2×2 average) and aliases (pixels between samples are
 * skipped), which loses eyelashes, hair and small print. This uses Lanczos-3 when shrinking, widened
 * by the reduction so every source pixel contributes, and Catmull-Rom when enlarging. Measured on a
 * portrait shrunk for a 50 KB limit, it keeps noticeably more detail at the same file size (see
 * DECISIONS.md).
 *
 * Rows are read and written through [RowSource] and [RowSink], and only the few source rows the
 * filter needs at once are held, so memory stays at a few rows however big the image is.
 * Transparent pixels are premultiplied while filtering, so a cut-out's edges don't pick up the
 * colour hidden under fully transparent pixels.
 */
object Resampler {

    fun interface RowSource {
        /** Fills [into] with row [y], [into]'s length being the source width. */
        fun read(y: Int, into: IntArray)
    }

    fun interface RowSink {
        fun write(y: Int, row: IntArray)
    }

    fun resize(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        hasAlpha: Boolean,
        source: RowSource,
        sink: RowSink,
    ) {
        require(sourceWidth > 0 && sourceHeight > 0 && targetWidth > 0 && targetHeight > 0) { "Empty image" }
        val columns = contributions(sourceWidth, targetWidth)
        val rows = contributions(sourceHeight, targetHeight)
        val channels = CHANNELS

        // Source rows resampled across, kept in a small ring while the output rows that need them
        // are made. Rows are visited in increasing order, so each is made once.
        val window = rows.maxTaps + 1
        val ring = Array(window) { FloatArray(targetWidth * channels) }
        val ringRow = IntArray(window) { -1 }
        val sourceRow = IntArray(sourceWidth)
        val premultiplied = FloatArray(sourceWidth * channels)
        val outRow = IntArray(targetWidth)
        val accumulated = FloatArray(targetWidth * channels)

        fun horizontal(y: Int): FloatArray {
            val slot = y % window
            if (ringRow[slot] == y) return ring[slot]
            source.read(y, sourceRow)
            for (x in 0 until sourceWidth) {
                val c = sourceRow[x]
                val a = (c ushr 24).toFloat()
                val f = if (hasAlpha) a / 255f else 1f
                val o = x * channels
                premultiplied[o] = a
                premultiplied[o + 1] = ((c shr 16) and 0xFF) * f
                premultiplied[o + 2] = ((c shr 8) and 0xFF) * f
                premultiplied[o + 3] = (c and 0xFF) * f
            }
            val out = ring[slot]
            for (x in 0 until targetWidth) {
                val start = columns.start[x]
                val weightBase = x * columns.maxTaps
                var a = 0f
                var r = 0f
                var g = 0f
                var b = 0f
                for (t in 0 until columns.count[x]) {
                    val w = columns.weights[weightBase + t]
                    val o = (start + t) * channels
                    a += premultiplied[o] * w
                    r += premultiplied[o + 1] * w
                    g += premultiplied[o + 2] * w
                    b += premultiplied[o + 3] * w
                }
                val o = x * channels
                out[o] = a
                out[o + 1] = r
                out[o + 2] = g
                out[o + 3] = b
            }
            ringRow[slot] = y
            return out
        }

        for (y in 0 until targetHeight) {
            accumulated.fill(0f)
            val start = rows.start[y]
            val weightBase = y * rows.maxTaps
            for (t in 0 until rows.count[y]) {
                val w = rows.weights[weightBase + t]
                val line = horizontal(start + t)
                for (i in accumulated.indices) accumulated[i] += line[i] * w
            }
            for (x in 0 until targetWidth) {
                val o = x * channels
                val a = clamp(accumulated[o], 0f, 255f)
                val r: Float
                val g: Float
                val b: Float
                if (hasAlpha) {
                    // Premultiplied colour can't exceed its alpha.
                    val scale = if (a > 0f) 255f / a else 0f
                    r = clamp(accumulated[o + 1], 0f, a) * scale
                    g = clamp(accumulated[o + 2], 0f, a) * scale
                    b = clamp(accumulated[o + 3], 0f, a) * scale
                } else {
                    r = accumulated[o + 1]
                    g = accumulated[o + 2]
                    b = accumulated[o + 3]
                }
                val alpha = if (hasAlpha) a.roundToInt() else 0xFF
                outRow[x] = (alpha shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
            }
            sink.write(y, outRow)
        }
    }

    /**
     * Unsharp mask for an opaque image, in place, row by row: each pixel moves away from a slight
     * blur of its neighbours by [amount], unless the difference is under [threshold] (so flat skin
     * and sky don't turn grainy). Shrinking softens edges; this restores the crispness a sharp
     * original had, the way photo editors' "sharpen for screen" export does.
     */
    fun sharpen(
        width: Int,
        height: Int,
        source: RowSource,
        sink: RowSink,
        amount: Float = SHARPEN_AMOUNT,
        sigma: Float = SHARPEN_SIGMA,
        threshold: Int = SHARPEN_THRESHOLD,
    ) {
        if (width < 3 || height < 3) {
            val row = IntArray(width)
            for (y in 0 until height) {
                source.read(y, row)
                sink.write(y, row)
            }
            return
        }
        val side = exp(-1f / (2f * sigma * sigma))
        val wSide = side / (1f + 2f * side)
        val wMid = 1f / (1f + 2f * side)
        // Three source rows at a time: above, this one, below.
        val lines = Array(3) { IntArray(width) }
        val lineRow = intArrayOf(-1, -1, -1)
        fun line(y: Int): IntArray {
            val clamped = y.coerceIn(0, height - 1)
            val slot = clamped % 3
            if (lineRow[slot] != clamped) {
                source.read(clamped, lines[slot])
                lineRow[slot] = clamped
            }
            return lines[slot]
        }
        val out = IntArray(width)
        val across = Array(3) { FloatArray(width * 3) }
        for (y in 0 until height) {
            val rowsAround = arrayOf(line(y - 1), line(y), line(y + 1))
            // Blur across each of the three rows, then down the middle.
            for (k in 0..2) {
                val src = rowsAround[k]
                val dst = across[k]
                for (x in 0 until width) {
                    val l = src[max(0, x - 1)]
                    val c = src[x]
                    val r = src[min(width - 1, x + 1)]
                    for (ch in 0..2) {
                        val shift = 16 - ch * 8
                        dst[x * 3 + ch] = wSide * ((l shr shift) and 0xFF) + wMid * ((c shr shift) and 0xFF) +
                            wSide * ((r shr shift) and 0xFF)
                    }
                }
            }
            val centre = rowsAround[1]
            for (x in 0 until width) {
                val c = centre[x]
                var pixel = c and 0xFF000000.toInt()
                for (ch in 0..2) {
                    val shift = 16 - ch * 8
                    val original = (c shr shift) and 0xFF
                    val blurred = wSide * across[0][x * 3 + ch] + wMid * across[1][x * 3 + ch] + wSide * across[2][x * 3 + ch]
                    val difference = original - blurred
                    val value = if (abs(difference) < threshold) original.toFloat() else original + amount * difference
                    pixel = pixel or (channel(value) shl shift)
                }
                out[x] = pixel
            }
            sink.write(y, out)
        }
    }

    /** Which source pixels make each target pixel, and how much each counts. */
    internal class Contributions(val start: IntArray, val count: IntArray, val weights: FloatArray, val maxTaps: Int)

    internal fun contributions(sourceLength: Int, targetLength: Int): Contributions {
        val ratio = sourceLength.toDouble() / targetLength
        val shrinking = ratio > 1.0
        // Shrinking widens the filter by the ratio, so it averages everything that maps to a pixel.
        val stretch = if (shrinking) ratio else 1.0
        val support = (if (shrinking) LANCZOS_LOBES.toDouble() else CUBIC_SUPPORT) * stretch
        val maxTaps = ceil(support * 2).toInt() + 2
        val start = IntArray(targetLength)
        val count = IntArray(targetLength)
        val weights = FloatArray(targetLength * maxTaps)
        for (i in 0 until targetLength) {
            val centre = (i + 0.5) * ratio - 0.5
            val first = max(0, floor(centre - support).toInt() + 1)
            val last = min(sourceLength - 1, ceil(centre + support).toInt() - 1).coerceAtLeast(first)
            var total = 0.0
            val taps = min(maxTaps, last - first + 1)
            val raw = DoubleArray(taps)
            for (t in 0 until taps) {
                val distance = (first + t - centre) / stretch
                raw[t] = if (shrinking) lanczos(distance) else catmullRom(distance)
                total += raw[t]
            }
            if (total == 0.0) {
                // Can't happen with these kernels, but never divide by zero: take the nearest pixel.
                raw.fill(0.0)
                raw[(centre.roundToInt() - first).coerceIn(0, taps - 1)] = 1.0
                total = 1.0
            }
            start[i] = first
            count[i] = taps
            for (t in 0 until taps) weights[i * maxTaps + t] = (raw[t] / total).toFloat()
        }
        return Contributions(start, count, weights, maxTaps)
    }

    private fun lanczos(x: Double): Double {
        val ax = abs(x)
        if (ax < 1e-9) return 1.0
        if (ax >= LANCZOS_LOBES) return 0.0
        val px = PI * x
        return LANCZOS_LOBES * sin(px) * sin(px / LANCZOS_LOBES) / (px * px)
    }

    private fun catmullRom(x: Double): Double {
        val ax = abs(x)
        return when {
            ax < 1.0 -> 1.5 * ax * ax * ax - 2.5 * ax * ax + 1.0
            ax < 2.0 -> -0.5 * ax * ax * ax + 2.5 * ax * ax - 4.0 * ax + 2.0
            else -> 0.0
        }
    }

    private fun clamp(value: Float, low: Float, high: Float): Float = if (value < low) low else if (value > high) high else value

    private fun channel(value: Float): Int = value.roundToInt().coerceIn(0, 255)

    private const val CHANNELS = 4
    private const val LANCZOS_LOBES = 3
    private const val CUBIC_SUPPORT = 2.0
    const val SHARPEN_AMOUNT = 0.4f
    const val SHARPEN_SIGMA = 0.6f
    const val SHARPEN_THRESHOLD = 2
}
