package app.formkit.core.imaging.scan

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

@Serializable
enum class ScanFilter { Original, Greyscale, BlackWhite, Enhanced }

/**
 * The looks a scanned page can have, applied to opaque ARGB pixels in place.
 *
 * Enhanced and black & white first estimate how bright the bare paper is across the page (see
 * [PaperLight]). Dividing each pixel by the paper under it cancels shadows and uneven light, so the
 * paper comes out white from edge to edge while ink, stamps and photos keep their contrast.
 */
object ScanFilters {

    fun apply(filter: ScanFilter, pixels: IntArray, width: Int, height: Int) {
        require(width > 0 && height > 0 && pixels.size == width * height) { "Pixel count doesn't match ${width}x$height" }
        when (filter) {
            ScanFilter.Original -> Unit
            ScanFilter.Greyscale -> for (i in pixels.indices) pixels[i] = gray(luma(pixels[i]))
            ScanFilter.Enhanced -> enhance(pixels, width, height)
            ScanFilter.BlackWhite -> blackAndWhite(pixels, width, height)
        }
    }

    /** Whitens the paper and deepens the ink, keeping colour. */
    private fun enhance(pixels: IntArray, width: Int, height: Int) {
        val paper = PaperLight.estimate(pixels, width, height)
        val light = FloatArray(width)
        for (y in 0 until height) {
            paper.fillRow(y, light)
            val row = y * width
            for (x in 0 until width) {
                val color = pixels[row + x]
                val gain = 255f / light[x]
                pixels[row + x] = opaque(
                    level(((color shr 16) and 0xFF) * gain),
                    level(((color shr 8) and 0xFF) * gain),
                    level((color and 0xFF) * gain),
                )
            }
        }
    }

    /** Ink black, paper white, with a narrow soft edge so text doesn't come out jagged. */
    private fun blackAndWhite(pixels: IntArray, width: Int, height: Int) {
        val paper = PaperLight.estimate(pixels, width, height)
        val light = FloatArray(width)
        for (y in 0 until height) {
            paper.fillRow(y, light)
            val row = y * width
            for (x in 0 until width) {
                val ratio = luma(pixels[row + x]) / light[x]
                val ink = ((INK_THRESHOLD - ratio) / INK_SOFTNESS).coerceIn(0f, 1f)
                pixels[row + x] = gray((255f * (1f - ink) + 0.5f).toInt())
            }
        }
    }

    /** Paper (at [WHITE_POINT] after correction) goes to white; the darkest ink goes to black. */
    private fun level(value: Float): Int = ((value - BLACK_POINT) * 255f / (WHITE_POINT - BLACK_POINT)).toInt().coerceIn(0, 255)

    private const val BLACK_POINT = 25f
    private const val WHITE_POINT = 235f

    /** A pixel this much darker than its paper counts as ink. Faint pencil sits around 0.7. */
    private const val INK_THRESHOLD = 0.75f
    private const val INK_SOFTNESS = 0.12f
}

/**
 * How bright the bare paper is at every point of a page, from a coarse grid.
 *
 * Each grid cell takes its 90th-percentile brightness: text rarely covers more than a tenth of a
 * cell, so that's the paper. Each cell then takes the brightest of its neighbours, which covers
 * cells filled by a heading or a dark mark, and the grid is smoothed so the estimate has no seams.
 */
internal class PaperLight private constructor(
    private val grid: FloatArray,
    private val columns: Int,
    private val rows: Int,
    private val cell: Int,
    width: Int,
) {
    private val leftCell = IntArray(width)
    private val rightCell = IntArray(width)
    private val rightWeight = FloatArray(width)

    init {
        for (x in 0 until width) {
            val position = ((x + 0.5f) / cell - 0.5f).coerceIn(0f, (columns - 1).toFloat())
            leftCell[x] = position.toInt()
            rightCell[x] = min(leftCell[x] + 1, columns - 1)
            rightWeight[x] = position - leftCell[x]
        }
    }

    /** Fills [out] with the paper brightness at each pixel of row [y], blending the four nearest cells. */
    fun fillRow(y: Int, out: FloatArray) {
        val position = ((y + 0.5f) / cell - 0.5f).coerceIn(0f, (rows - 1).toFloat())
        val top = position.toInt() * columns
        val bottom = min(position.toInt() + 1, rows - 1) * columns
        val downWeight = position - position.toInt()
        for (x in out.indices) {
            val upper = grid[top + leftCell[x]] + (grid[top + rightCell[x]] - grid[top + leftCell[x]]) * rightWeight[x]
            val lower = grid[bottom + leftCell[x]] + (grid[bottom + rightCell[x]] - grid[bottom + leftCell[x]]) * rightWeight[x]
            out[x] = upper + (lower - upper) * downWeight
        }
    }

    companion object {
        fun estimate(pixels: IntArray, width: Int, height: Int): PaperLight {
            val cell = max(MIN_CELL, max(width, height) / CELLS_ALONG_LONG_EDGE)
            val columns = (width + cell - 1) / cell
            val rows = (height + cell - 1) / cell
            val grid = FloatArray(columns * rows)
            val histogram = IntArray(HISTOGRAM_BINS)
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    histogram.fill(0)
                    val left = column * cell
                    val right = min(width, left + cell)
                    val top = row * cell
                    val bottom = min(height, top + cell)
                    for (y in top until bottom) {
                        for (x in left until right) histogram[luma(pixels[y * width + x]) * HISTOGRAM_BINS / 256]++
                    }
                    var brighter = ((right - left) * (bottom - top) * BRIGHTEST_SHARE).toInt()
                    var bin = HISTOGRAM_BINS - 1
                    while (bin > 0) {
                        brighter -= histogram[bin]
                        if (brighter < 0) break
                        bin--
                    }
                    grid[row * columns + column] = (bin + 0.5f) * 256f / HISTOGRAM_BINS
                }
            }
            val filled = neighbourhood(grid, columns, rows) { values, count -> values.take(count).max() }
            val smoothed = neighbourhood(filled, columns, rows) { values, count -> values.take(count).sum() / count }
            for (i in smoothed.indices) smoothed[i] = max(smoothed[i], MIN_PAPER)
            return PaperLight(smoothed, columns, rows, cell, width)
        }

        private inline fun neighbourhood(grid: FloatArray, columns: Int, rows: Int, combine: (FloatArray, Int) -> Float): FloatArray {
            val result = FloatArray(grid.size)
            val values = FloatArray(9)
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    var count = 0
                    for (dy in -1..1) {
                        val y = row + dy
                        if (y < 0 || y >= rows) continue
                        for (dx in -1..1) {
                            val x = column + dx
                            if (x < 0 || x >= columns) continue
                            values[count++] = grid[y * columns + x]
                        }
                    }
                    result[row * columns + column] = combine(values, count)
                }
            }
            return result
        }

        private const val CELLS_ALONG_LONG_EDGE = 64
        private const val MIN_CELL = 4
        private const val HISTOGRAM_BINS = 128
        private const val BRIGHTEST_SHARE = 0.1f

        /** Keeps the division sane on a page that's nearly black. */
        private const val MIN_PAPER = 40f
    }
}

internal fun luma(color: Int): Int = (((color shr 16) and 0xFF) * 77 + ((color shr 8) and 0xFF) * 150 + (color and 0xFF) * 29) shr 8

private fun gray(value: Int): Int = opaque(value, value, value)

private fun opaque(red: Int, green: Int, blue: Int): Int = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
