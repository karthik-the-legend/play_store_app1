package app.formkit.core.imaging.scan

import app.formkit.core.imaging.PixelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageCornersTest {

    private val photo = PixelSize(400, 300)

    @Test
    fun `corners in any order come out clockwise from the top left`() {
        val xs = doubleArrayOf(380.0, 20.0, 360.0, 40.0)
        val ys = doubleArrayOf(280.0, 10.0, 30.0, 290.0)

        val corners = PageCorners.fromPixels(xs, ys, photo)

        assertPoint(20f / 400, 10f / 300, corners.topLeft)
        assertPoint(360f / 400, 30f / 300, corners.topRight)
        assertPoint(380f / 400, 280f / 300, corners.bottomRight)
        assertPoint(40f / 400, 290f / 300, corners.bottomLeft)
    }

    private fun assertPoint(x: Float, y: Float, actual: PagePoint) {
        assertEquals(x, actual.x, 1e-6f)
        assertEquals(y, actual.y, 1e-6f)
    }

    @Test
    fun `the whole photo is usable, a crossed or tiny outline is not`() {
        assertTrue(PageCorners.WholePhoto.isUsable(photo))

        val crossed = PageCorners.WholePhoto.copy(topRight = PagePoint(1f, 1f), bottomRight = PagePoint(1f, 0f))
        assertFalse(crossed.isUsable(photo))

        val tiny = PageCorners(PagePoint(0.5f, 0.5f), PagePoint(0.51f, 0.5f), PagePoint(0.51f, 0.51f), PagePoint(0.5f, 0.51f))
        assertFalse(tiny.isUsable(photo))
    }

    @Test
    fun `a corner dragged inside the page makes it unusable`() {
        val folded = PageCorners.WholePhoto.moved(2, PagePoint(0.2f, 0.2f))

        assertFalse(folded.isUsable(photo))
    }

    @Test
    fun `moved corners stay on the photo`() {
        val moved = PageCorners.WholePhoto.moved(0, PagePoint(-0.3f, 1.4f))

        assertEquals(PagePoint(0f, 1f), moved.topLeft)
    }

    @Test
    fun `the straightened size uses the longer of opposite sides and is capped`() {
        // A page seen at an angle: the top edge is foreshortened to 200 px, the bottom is 300.
        val trapezoid = PageCorners.fromPixels(doubleArrayOf(100.0, 300.0, 350.0, 50.0), doubleArrayOf(0.0, 0.0, 300.0, 300.0), photo)

        val size = trapezoid.outputSize(photo, maxLongEdge = 4000)
        assertEquals(300, size.width)
        assertEquals(304, size.height)

        val capped = trapezoid.outputSize(photo, maxLongEdge = 152)
        assertEquals(150, capped.width)
        assertEquals(152, capped.height)
    }

    @Test
    fun `turning a quarter turn swaps the page's width and height, and four turns change nothing`() {
        val wide = PageCorners.fromPixels(doubleArrayOf(0.0, 400.0, 400.0, 0.0), doubleArrayOf(50.0, 50.0, 250.0, 250.0), photo)

        val turned = wide.rotatedClockwise()

        assertEquals(PixelSize(400, 200), wide.outputSize(photo, 4000))
        assertEquals(PixelSize(200, 400), turned.outputSize(photo, 4000))
        assertTrue(turned.isUsable(photo))
        assertEquals(wide, turned.rotatedClockwise().rotatedClockwise().rotatedClockwise())
    }
}
