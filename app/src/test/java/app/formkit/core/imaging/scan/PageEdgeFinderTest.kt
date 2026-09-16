package app.formkit.core.imaging.scan

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class PageEdgeFinderTest {

    private val tilted = listOf(90.0 to 40.0, 400.0 to 70.0, 380.0 to 330.0, 60.0 to 300.0)

    @Test
    fun `a tilted page on a dark table is found`() {
        val scene = PhotoScene().table(70).page(tilted).noise(6)

        assertCorners(scene, PageEdgeFinder.find(scene.gray()), tilted)
    }

    @Test
    fun `a page photographed at an angle is found`() {
        val trapezoid = listOf(130.0 to 50.0, 350.0 to 50.0, 430.0 to 330.0, 50.0 to 330.0)
        val scene = PhotoScene().table(60).page(trapezoid).noise(6)

        assertCorners(scene, PageEdgeFinder.find(scene.gray()), trapezoid)
    }

    @Test
    fun `a white page on a light table is found`() {
        val scene = PhotoScene().table(185).page(tilted, paper = 225).noise(8)

        assertCorners(scene, PageEdgeFinder.find(scene.gray()), tilted)
    }

    @Test
    fun `a cluttered table doesn't hide the page`() {
        val scene = PhotoScene().table(90).clutter(60).page(tilted).noise(6)

        assertCorners(scene, PageEdgeFinder.find(scene.gray()), tilted)
    }

    @Test
    fun `a box printed on the page isn't mistaken for its outline`() {
        val scene = PhotoScene().table(75).page(tilted, box = true).noise(5)

        assertCorners(scene, PageEdgeFinder.find(scene.gray()), tilted)
    }

    @Test
    fun `a thumb over one side still finds the page`() {
        val scene = PhotoScene().table(70).page(tilted).blob(centreX = 220, centreY = 318, radiusX = 30, radiusY = 45, shade = 150).noise(6)

        assertCorners(scene, PageEdgeFinder.find(scene.gray()), tilted)
    }

    @Test
    fun `a photo with no page finds nothing`() {
        val scene = PhotoScene().table(100).clutter(80).noise(8)

        assertNull(PageEdgeFinder.find(scene.gray()))
    }

    @Test
    fun `a page running off the photo finds nothing`() {
        val overflowing = listOf(100.0 to -80.0, 380.0 to -60.0, 400.0 to 450.0, 80.0 to 430.0)
        val scene = PhotoScene().table(70).page(overflowing, text = false).noise(6)

        assertNull(PageEdgeFinder.find(scene.gray()))
    }

    private fun assertCorners(scene: PhotoScene, found: EdgeFinding?, expected: List<Pair<Double, Double>>) {
        assertNotNull("no page found", found)
        val points = found!!.corners.points
        points.forEachIndexed { index, point ->
            val (x, y) = expected[index]
            val distance = hypot(point.x * scene.width - x, point.y * scene.height - y)
            assertTrue(
                "corner $index at (${point.x * scene.width}, ${point.y * scene.height}) is ${"%.1f".format(distance)} px from ($x, $y)",
                distance <= TOLERANCE_PX,
            )
        }
    }

    private companion object {
        /** About 1% of the long edge: a sliver of table, at most, once straightened. */
        const val TOLERANCE_PX = 5.0
    }
}
