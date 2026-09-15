package app.formkit.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageRangesTest {

    @Test
    fun `single pages and ranges are read in order`() {
        val parsed = PageRanges.parse("1-3, 5, 8-9", pageCount = 10)

        assertEquals(RangeParse.Valid(listOf(PageRange(1, 3), PageRange(5, 5), PageRange(8, 9))), parsed)
    }

    @Test
    fun `open-ended ranges run to the start or the end`() {
        val parsed = PageRanges.parse("-2; 9-", pageCount = 12) as RangeParse.Valid

        assertEquals(listOf(PageRange(1, 2), PageRange(9, 12)), parsed.ranges)
    }

    @Test
    fun `spaces and typographic dashes are fine`() {
        val parsed = PageRanges.parse(" 2 – 4 ,6—7 ", pageCount = 7) as RangeParse.Valid

        assertEquals(listOf(PageRange(2, 4), PageRange(6, 7)), parsed.ranges)
    }

    @Test
    fun `nothing typed is empty, not an error`() {
        assertEquals(RangeParse.Empty, PageRanges.parse(" , ", pageCount = 3))
    }

    @Test
    fun `pages outside the document are named`() {
        assertEquals(RangeParse.OutOfBounds(0, 5), PageRanges.parse("0-2", pageCount = 5))
        assertEquals(RangeParse.OutOfBounds(6, 5), PageRanges.parse("2, 6", pageCount = 5))
        assertEquals(RangeParse.OutOfBounds(9, 5), PageRanges.parse("4-9", pageCount = 5))
    }

    @Test
    fun `backwards ranges and junk are invalid`() {
        assertEquals(RangeParse.Invalid("5-2"), PageRanges.parse("1, 5-2", pageCount = 9))
        assertEquals(RangeParse.Invalid("a"), PageRanges.parse("a", pageCount = 9))
        assertEquals(RangeParse.Invalid("1-2-3"), PageRanges.parse("1-2-3", pageCount = 9))
        assertEquals(RangeParse.Invalid("-"), PageRanges.parse("-", pageCount = 9))
    }

    @Test
    fun `page indexes keep the typed order and drop repeats`() {
        val ranges = (PageRanges.parse("3-4, 1, 4-5", pageCount = 5) as RangeParse.Valid).ranges

        assertEquals(listOf(2, 3, 0, 4), PageRanges.pageIndices(ranges))
    }

    @Test
    fun `selected pages are written back as short ranges`() {
        assertEquals("1-3, 5, 7-8", PageRanges.format(listOf(7, 0, 2, 1, 4, 6)))
        assertEquals("4", PageRanges.format(setOf(3)))
        assertTrue(PageRanges.format(emptyList()).isEmpty())
    }
}
