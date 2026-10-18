package app.formkit.core.imaging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class ResamplerTest {

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int) = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    private fun gray(value: Int) = argb(255, value, value, value)
    private fun red(pixel: Int) = (pixel shr 16) and 0xFF
    private fun green(pixel: Int) = (pixel shr 8) and 0xFF
    private fun alpha(pixel: Int) = pixel ushr 24

    private fun resize(pixels: IntArray, width: Int, height: Int, toWidth: Int, toHeight: Int, hasAlpha: Boolean = false): IntArray {
        val out = IntArray(toWidth * toHeight)
        Resampler.resize(
            width, height, toWidth, toHeight, hasAlpha,
            source = { y, into -> System.arraycopy(pixels, y * width, into, 0, width) },
            sink = { y, row -> System.arraycopy(row, 0, out, y * toWidth, toWidth) },
        )
        return out
    }

    private fun sharpen(pixels: IntArray, width: Int, height: Int): IntArray {
        val out = pixels.copyOf()
        Resampler.sharpen(
            width, height,
            source = { y, into -> System.arraycopy(out, y * width, into, 0, width) },
            sink = { y, row -> System.arraycopy(row, 0, out, y * width, width) },
        )
        return out
    }

    @Test
    fun `the same size gives the same pixels`() {
        val pixels = IntArray(7 * 5) { i -> gray((i * 37) % 256) }

        assertArrayEquals(pixels, resize(pixels, 7, 5, 7, 5))
    }

    @Test
    fun `a flat colour stays exactly flat, shrinking or growing`() {
        val pixels = IntArray(40 * 30) { argb(255, 200, 120, 40) }

        assertTrue(resize(pixels, 40, 30, 23, 17).all { it == argb(255, 200, 120, 40) })
        assertTrue(resize(pixels, 40, 30, 61, 45).all { it == argb(255, 200, 120, 40) })
    }

    @Test
    fun `fine stripes shrunk by an uneven amount average out instead of aliasing`() {
        // One-pixel black and white stripes: finer than a 1.5x smaller image can show. A bilinear
        // sample would land on stripes unevenly and draw bold false bands; the right answer is grey.
        val width = 90
        val height = 4
        val pixels = IntArray(width * height) { i -> gray(if ((i % width) % 2 == 0) 0 else 255) }

        val out = resize(pixels, width, height, 60, height)

        val row = (0 until 60).map { red(out[it]) }.drop(3).dropLast(3)
        val mean = row.average()
        val spread = sqrt(row.sumOf { (it - mean) * (it - mean) } / row.size)
        assertEquals(127.5, mean, 8.0)
        assertTrue("stripes left a pattern with spread $spread", spread < 12)
    }

    @Test
    fun `colour under fully transparent pixels doesn't leak into a cut-out's edge`() {
        // Left half: opaque red person. Right half: transparent, but its hidden colour is green.
        val width = 40
        val height = 6
        val pixels = IntArray(width * height) { i -> if (i % width < 20) argb(255, 220, 30, 30) else argb(0, 0, 255, 0) }

        val out = resize(pixels, width, height, 27, height, hasAlpha = true)

        for (x in 0 until 27) {
            val pixel = out[2 * 27 + x]
            if (alpha(pixel) > 8) assertTrue("pixel $x went green: ${Integer.toHexString(pixel)}", green(pixel) < 60 && red(pixel) > 150)
        }
        assertEquals(255, alpha(out[2 * 27]))
        assertEquals(0, alpha(out[2 * 27 + 26]))
    }

    @Test
    fun `a hard edge stays hard after shrinking`() {
        val width = 60
        val pixels = IntArray(width * 3) { i -> gray(if (i % width < 30) 20 else 230) }

        val out = resize(pixels, width, 3, 40, 3)

        // Count the pixels part-way between the two sides: a soft (blurred) edge has several.
        val between = (0 until 40).count { red(out[40 + it]) in 40..210 }
        assertTrue("edge spread over $between pixels", between <= 2)
    }

    @Test
    fun `each output pixel's weights add up to one`() {
        for ((from, to) in listOf(100 to 37, 37 to 100, 51 to 50, 2 to 1, 1 to 5)) {
            val c = Resampler.contributions(from, to)
            for (i in 0 until to) {
                var total = 0f
                for (t in 0 until c.count[i]) total += c.weights[i * c.maxTaps + t]
                assertEquals("$from to $to, pixel $i", 1f, total, 1e-4f)
                assertTrue(c.start[i] >= 0 && c.start[i] + c.count[i] <= from)
            }
        }
    }

    @Test
    fun `sharpening leaves flat areas and faint noise alone`() {
        val width = 20
        val height = 10
        // Flat grey with a one-level ripple: under the threshold, so skin and sky don't turn grainy.
        val pixels = IntArray(width * height) { i -> gray(128 + (i % 2)) }

        assertArrayEquals(pixels, sharpen(pixels, width, height))
    }

    @Test
    fun `sharpening makes an edge crisper without touching what's far from it`() {
        val width = 20
        val height = 5
        // A soft edge: 60, 60, ..., 100, 140, 180, ..., 180.
        val ramp = intArrayOf(60, 60, 60, 60, 60, 60, 60, 60, 100, 140, 180, 180, 180, 180, 180, 180, 180, 180, 180, 180)
        val pixels = IntArray(width * height) { i -> gray(ramp[i % width]) }

        val out = sharpen(pixels, width, height)

        val row = (0 until width).map { red(out[2 * width + it]) }
        assertTrue("dark side got darker next to the edge: $row", row[7] < 60)
        assertTrue("light side got lighter next to the edge: $row", row[10] > 180)
        assertEquals(60, row[2])
        assertEquals(180, row[17])
        assertTrue("overshoot stays small: $row", abs(row[7] - 60) <= 12 && abs(row[10] - 180) <= 12)
    }
}
