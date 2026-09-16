package app.formkit.core.imaging.scan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanFiltersTest {

    private val width = 400
    private val height = 300

    /** Paper lit from the left, falling into shadow on the right, with rows of dark text. */
    private fun shadedPage(): IntArray = IntArray(width * height) { i ->
        val x = i % width
        val y = i / width
        val paper = 230 - x * 100 / width
        val shade = if (isText(x, y)) (paper * 0.35f).toInt() else paper
        rgb(shade, shade, shade)
    }

    private fun isText(x: Int, y: Int) = x in 40 until 360 && y in 30 until 270 && y % 20 in 8..11

    /** More than 3 px away from any text row. */
    private fun isClearPaper(x: Int, y: Int) = !(x in 37 until 363 && y in 27 until 273 && y % 20 in 5..14)

    @Test
    fun `original leaves the pixels alone`() {
        val pixels = shadedPage()

        ScanFilters.apply(ScanFilter.Original, pixels, width, height)

        assertArrayEquals(shadedPage(), pixels)
    }

    @Test
    fun `greyscale makes every channel equal`() {
        val pixels = IntArray(width * height) { rgb(200, 40, 90) }

        ScanFilters.apply(ScanFilter.Greyscale, pixels, width, height)

        val (red, green, blue) = channels(pixels[0])
        assertEquals(red, green)
        assertEquals(green, blue)
    }

    @Test
    fun `enhanced turns shaded paper white and keeps the text dark`() {
        val pixels = shadedPage()

        ScanFilters.apply(ScanFilter.Enhanced, pixels, width, height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val (red, _, _) = channels(pixels[y * width + x])
                if (isClearPaper(x, y)) assertTrue("paper at $x,$y is $red", red >= 240)
                if (isText(x, y) && y % 20 in 9..10) assertTrue("text at $x,$y is $red", red <= 110)
            }
        }
    }

    @Test
    fun `enhanced keeps a red stamp red`() {
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            if (x in 150 until 190 && y in 120 until 128) rgb(190, 40, 40) else rgb(215, 215, 215)
        }

        ScanFilters.apply(ScanFilter.Enhanced, pixels, width, height)

        val (red, green, blue) = channels(pixels[124 * width + 170])
        assertTrue("stamp came out ($red, $green, $blue)", red >= 180 && green <= 80 && blue <= 80)
    }

    @Test
    fun `black and white gives white paper and black text despite the shadow`() {
        val pixels = shadedPage()

        ScanFilters.apply(ScanFilter.BlackWhite, pixels, width, height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val (red, _, _) = channels(pixels[y * width + x])
                if (isClearPaper(x, y)) assertEquals("paper at $x,$y", 255, red)
                if (isText(x, y) && y % 20 in 9..10) assertEquals("text at $x,$y", 0, red)
            }
        }
    }

    private fun rgb(red: Int, green: Int, blue: Int) = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

    private fun channels(color: Int) = Triple((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)
}
