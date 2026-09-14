package app.formkit.core.imaging.passport

import app.formkit.core.imaging.PixelSize
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PrintSheetTest {

    private val india = PixelSize(413, 531)
    private val us = PixelSize(600, 600)

    @Test
    fun `eight Indian passport photos fit on a landscape 4x6 sheet with gaps`() {
        assertEquals(8, PrintSheets.capacity(india))
        assertEquals(listOf(1, 2, 4, 6, 8), PrintSheets.countOptions(india))
        assertEquals(8, PrintSheets.defaultCount(india))

        val layout = PrintSheets.layout(india, 8)!!

        assertEquals(PixelSize(1800, 1200), layout.sheet)
        assertEquals(8, layout.slots.size)
        assertTrue("roomy spacing when it fits", layout.margin > 0 && layout.gap > 0)
        assertSlotsInsideAndApart(layout)
    }

    @Test
    fun `six 2x2 inch photos tile a 4x6 sheet edge to edge`() {
        assertEquals(6, PrintSheets.capacity(us))
        assertEquals(listOf(1, 2, 4, 6), PrintSheets.countOptions(us))

        val layout = PrintSheets.layout(us, 6)!!

        assertEquals(0, layout.margin)
        assertEquals(0, layout.gap)
        assertSlotsInsideAndApart(layout)
        assertNull(PrintSheets.layout(us, 8))
    }

    @Test
    fun `two 2x2 inch photos still get gaps`() {
        val layout = PrintSheets.layout(us, 2)!!

        assertTrue(layout.margin > 0 && layout.gap > 0)
    }

    @Test
    fun `a partial grid is centred on the sheet`() {
        val layout = PrintSheets.layout(india, 2)!!

        val left = layout.slots.minOf { it.left }
        val right = layout.slots.maxOf { it.left + it.width }
        val top = layout.slots.minOf { it.top }
        val bottom = layout.slots.maxOf { it.top + it.height }
        assertTrue(abs((layout.sheet.width - right) - left) <= 1)
        assertTrue(abs((layout.sheet.height - bottom) - top) <= 1)
    }

    @Test
    fun `photos keep their exact pixel size on the sheet`() {
        PrintSheets.layout(india, 6)!!.slots.forEach {
            assertEquals(413, it.width)
            assertEquals(531, it.height)
        }
    }

    @Test
    fun `a photo too big for the sheet can't be laid out`() {
        val poster = PixelSize(1500, 2000)

        assertEquals(0, PrintSheets.capacity(poster))
        assertEquals(emptyList<Int>(), PrintSheets.countOptions(poster))
        assertNull(PrintSheets.layout(poster, 1))
    }

    @Test
    fun `the JFIF header is set to 300 DPI without changing the size`() {
        val jpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
            'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0x00,
            0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0xFF.toByte(), 0xD9.toByte(),
        )

        val edited = JpegDensity.withDpi(jpeg, 300)

        assertEquals(jpeg.size, edited.size)
        assertEquals(300, JpegDensity.readDpi(edited))
        assertArrayEquals(jpeg.copyOfRange(18, jpeg.size), edited.copyOfRange(18, edited.size))
    }

    @Test
    fun `a JPEG without a JFIF header gets one`() {
        val bare = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDB.toByte(), 0x00, 0x02, 0xFF.toByte(), 0xD9.toByte())

        val edited = JpegDensity.withDpi(bare, 300)

        assertEquals(bare.size + 18, edited.size)
        assertEquals(300, JpegDensity.readDpi(edited))
        assertArrayEquals(bare.copyOfRange(2, bare.size), edited.copyOfRange(20, edited.size))
    }

    private fun assertSlotsInsideAndApart(layout: SheetLayout) {
        layout.slots.forEach { slot ->
            assertTrue("inside the margin: $slot", slot.left >= layout.margin && slot.top >= layout.margin)
            assertTrue(slot.left + slot.width <= layout.sheet.width - layout.margin)
            assertTrue(slot.top + slot.height <= layout.sheet.height - layout.margin)
        }
        for (a in layout.slots) for (b in layout.slots) {
            if (a === b) continue
            val apart = a.left + a.width + layout.gap <= b.left || b.left + b.width + layout.gap <= a.left ||
                a.top + a.height + layout.gap <= b.top || b.top + b.height + layout.gap <= a.top
            assertTrue("$a and $b overlap or are too close", apart)
        }
    }
}
