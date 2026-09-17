package app.formkit.core.imaging.passport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeColorsTest {

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int) = (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    /**
     * A 40 × 20 strip: a red person on the left half, a green wall on the right, and a one-pixel soft
     * edge between them whose colour is half and half, the way a camera records it.
     */
    private fun strip(): IntArray = IntArray(40 * 20) { i ->
        when (i % 40) {
            in 0 until 20 -> argb(255, 200, 30, 30)
            20 -> argb(128, 120, 110, 40)
            else -> argb(0, 40, 190, 50)
        }
    }

    @Test
    fun `a soft edge pixel takes the person's colour instead of the wall's`() {
        val pixels = strip()

        EdgeColors.decontaminate(pixels, 40, 20)

        val edge = pixels[10 * 40 + 20]
        assertEquals("alpha is untouched", 128, edge ushr 24)
        val red = (edge shr 16) and 0xFF
        val green = (edge shr 8) and 0xFF
        assertTrue("edge came out red=$red green=$green", red > 180 && green < 50)
    }

    @Test
    fun `solid and empty pixels are left alone`() {
        val pixels = strip()
        val before = pixels.copyOf()

        EdgeColors.decontaminate(pixels, 40, 20)

        for (i in pixels.indices) {
            if (i % 40 != 20) assertEquals("pixel $i", before[i], pixels[i])
        }
    }

    @Test
    fun `only the given rectangle is changed`() {
        val pixels = strip()
        val before = pixels.copyOf()

        EdgeColors.decontaminate(pixels, 40, 20, left = 0, top = 0, right = 40, bottom = 10)

        assertTrue("inside the rectangle the edge changed", pixels[5 * 40 + 20] != before[5 * 40 + 20])
        assertEquals("below it nothing did", before[15 * 40 + 20], pixels[15 * 40 + 20])
    }

    @Test
    fun `a cut-out with no soft edge is returned as it was`() {
        val pixels = IntArray(30 * 30) { i -> if (i % 30 < 15) argb(255, 10, 20, 30) else 0 }
        val before = pixels.copyOf()

        EdgeColors.decontaminate(pixels, 30, 30)

        assertTrue(pixels.contentEquals(before))
    }
}
