package app.formkit.core.pdf

import app.formkit.core.imaging.PixelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageLayoutTest {

    @Test
    fun `a portrait photo fills an A4 page inside the margins, centred`() {
        val placement = PageLayout.place(PixelSize(3000, 4000), PaperSize.A4, PageOrientation.Auto, PageMargin.Small)

        assertEquals(595.28f, placement.pageWidth, DELTA)
        assertEquals(841.89f, placement.pageHeight, DELTA)
        val image = placement.image
        // Width is the tighter side: 595.28 - 36 = 559.28 points wide, 745.7 tall.
        assertEquals(559.28f, image.width, DELTA)
        assertEquals(559.28f * 4 / 3, image.height, DELTA)
        assertEquals(18f, image.left, DELTA)
        assertEquals((841.89f - image.height) / 2, image.bottom, DELTA)
    }

    @Test
    fun `auto turns the page sideways for a landscape photo`() {
        val placement = PageLayout.place(PixelSize(4000, 3000), PaperSize.Letter, PageOrientation.Auto, PageMargin.None)

        assertEquals(792f, placement.pageWidth, DELTA)
        assertEquals(612f, placement.pageHeight, DELTA)
        // 4:3 is wider than the page's 792:612, so the width sets the scale: 792 × 594 points.
        assertEquals(792f, placement.image.width, DELTA)
        assertEquals(594f, placement.image.height, DELTA)
        assertEquals(9f, placement.image.bottom, DELTA)
    }

    @Test
    fun `a chosen orientation wins over the photo's shape`() {
        val placement = PageLayout.place(PixelSize(4000, 3000), PaperSize.A4, PageOrientation.Portrait, PageMargin.Large)

        assertEquals(595.28f, placement.pageWidth, DELTA)
        assertEquals(595.28f - 72f, placement.image.width, DELTA)
        assertTrue(placement.image.height < placement.pageHeight - 72f)
    }

    @Test
    fun `fit to image makes the page the image's shape at 150 DPI plus margins`() {
        val placement = PageLayout.place(PixelSize(1500, 3000), PaperSize.FitToImage, PageOrientation.Landscape, PageMargin.Small)

        // 1500 px at 150 DPI is 10 inches, 720 points.
        assertEquals(720f, placement.image.width, DELTA)
        assertEquals(1440f, placement.image.height, DELTA)
        assertEquals(720f + 36f, placement.pageWidth, DELTA)
        assertEquals(1440f + 36f, placement.pageHeight, DELTA)
        assertEquals(PointRect(18f, 18f, 720f, 1440f), placement.image)
    }

    @Test
    fun `fit to image never makes a page beyond what viewers support`() {
        val placement = PageLayout.place(PixelSize(8000, 100_000 / 2), PaperSize.FitToImage, PageOrientation.Auto, PageMargin.None)

        assertTrue(placement.pageHeight <= PageLayout.MAX_PAGE_POINTS)
        assertEquals(8000f / 50_000f, placement.image.width / placement.image.height, 0.001f)
    }

    private companion object {
        const val DELTA = 0.05f
    }
}
