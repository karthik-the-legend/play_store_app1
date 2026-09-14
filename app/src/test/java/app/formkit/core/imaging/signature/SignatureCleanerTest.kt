package app.formkit.core.imaging.signature

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

class SignatureCleanerTest {

    @Test
    fun `a shadow across textured paper comes out as clean paper`() {
        val page = Page(600, 300)
        // Bright on the left, deep shadow on the right, grainy throughout.
        page.fill { x, _ -> 235 - x * 120 / 600 }
        page.stroke(fromX = 150, toX = 450, centreY = 150, amplitude = 50)
        page.noise(10)

        val mask = page.clean()

        assertEquals("paper pixels marked as ink", 0, page.strayInk(mask))
        page.centreLine(150, 450, 150, 50).forEach { (x, y) -> assertEquals("ink at $x,$y", 255, mask.at(x, y)) }
    }

    @Test
    fun `ruled lines on the page are not ink`() {
        val page = Page(600, 300)
        page.fill { _, y -> if (y % 30 < 2) (230 * 0.85f).roundToInt() else 230 }
        page.stroke(fromX = 150, toX = 450, centreY = 150, amplitude = 40)
        page.noise(8)

        val mask = page.clean()

        assertEquals("line or paper pixels marked as ink", 0, page.strayInk(mask))
    }

    @Test
    fun `a hard shadow edge across the photo is removed but the signature stays`() {
        val page = Page(600, 300)
        page.fill { x, _ -> if (x < 300) 232 else 120 }
        page.stroke(fromX = 40, toX = 240, centreY = 150, amplitude = 50)
        page.noise(4)

        val mask = page.clean()

        var inkOnShadowSide = 0
        for (y in 0 until 300) for (x in 250 until 600) if (mask.at(x, y) > 0) inkOnShadowSide++
        assertEquals("pixels left along the shadow edge", 0, inkOnShadowSide)
        page.centreLine(40, 240, 150, 50).forEach { (x, y) -> assertEquals("ink at $x,$y", 255, mask.at(x, y)) }
    }

    @Test
    fun `raising ink strength brings out faint strokes`() {
        val page = Page(600, 300)
        page.fill { _, _ -> 230 }
        page.stroke(fromX = 150, toX = 450, centreY = 150, amplitude = 40, darkness = 0.72f)
        val line = page.centreLine(150, 450, 150, 40)

        val weakest = page.clean(strength = 0f)
        val strongest = page.clean(strength = 1f)

        assertTrue("faint stroke hidden at the lowest strength", line.all { (x, y) -> weakest.at(x, y) == 0 })
        assertTrue("faint stroke solid at the highest strength", line.all { (x, y) -> strongest.at(x, y) == 255 })
    }

    @Test
    fun `specks are removed but the dot of an i stays`() {
        val page = Page(600, 300)
        page.fill { _, _ -> 230 }
        page.square(left = 100, top = 100, size = 2, value = 30)
        page.square(left = 300, top = 150, size = 6, value = 30)

        val mask = page.clean()

        assertEquals(0, mask.at(100, 100))
        assertEquals(0, mask.at(101, 101))
        assertEquals(255, mask.at(302, 152))
    }

    @Test
    fun `ink bounds wrap the signature with a margin`() {
        val page = Page(600, 300)
        page.fill { _, _ -> 230 }
        page.stroke(fromX = 150, toX = 450, centreY = 150, amplitude = 50)

        val bounds = requireNotNull(SignatureCleaner.findInkBounds(page.clean()))

        // The stroke covers x 146–454 and y 96–204; the margin is 8% of its longer side.
        assertTrue("left ${bounds.left}", bounds.left in 110..140)
        assertTrue("right ${bounds.right}", bounds.right in 460..495)
        assertTrue("top ${bounds.top}", bounds.top in 60..90)
        assertTrue("bottom ${bounds.bottom}", bounds.bottom in 210..245)
    }

    @Test
    fun `a blank page has no ink`() {
        val page = Page(600, 300)
        page.fill { _, _ -> 228 }
        page.noise(10)

        assertNull(SignatureCleaner.findInkBounds(page.clean()))
    }

    @Test
    fun `transparent output leaves paper fully transparent`() {
        val mask = InkMask(3, 1, byteArrayOf(0, 128.toByte(), 255.toByte()))
        val whole = InkBounds(0, 0, 3, 1)

        assertArrayEquals(
            intArrayOf(0x00000000, 0x80000000.toInt(), 0xFF000000.toInt()),
            SignatureRenderer.toArgb(mask, whole, transparent = true),
        )
        assertArrayEquals(
            intArrayOf(0xFFFFFFFF.toInt(), 0xFF7F7F7F.toInt(), 0xFF000000.toInt()),
            SignatureRenderer.toArgb(mask, whole, transparent = false),
        )
    }

    @Test
    fun `rendering reads only the cropped area`() {
        val mask = InkMask(4, 2, byteArrayOf(0, 10, 20, 30, 40, 50, 60, 70))

        val pixels = SignatureRenderer.toArgb(mask, InkBounds(1, 0, 3, 2), transparent = true)

        assertArrayEquals(intArrayOf(10 shl 24, 20 shl 24, 50 shl 24, 60 shl 24), pixels)
    }

    @Test
    fun `drawn strokes become ink through their alpha`() {
        val mask = InkMask.fromAlpha(2, 1, intArrayOf(0x00FFFFFF, 0xC0000000.toInt()))

        assertEquals(0, mask.at(0, 0))
        assertEquals(0xC0, mask.at(1, 0))
    }

    /** A synthetic grey page: paper, then pen strokes darkening it, then sensor noise on top. */
    private class Page(val width: Int, val height: Int) {
        val gray = IntArray(width * height)
        val ink = BooleanArray(width * height)

        fun fill(paper: (x: Int, y: Int) -> Int) {
            for (y in 0 until height) for (x in 0 until width) gray[y * width + x] = paper(x, y)
        }

        fun noise(amount: Int, seed: Int = 7) {
            val random = Random(seed)
            for (i in gray.indices) gray[i] = (gray[i] + random.nextInt(-amount, amount + 1)).coerceIn(0, 255)
        }

        /** A pen stroke along one period of a sine wave. Ink is [darkness] times the paper under it. */
        fun stroke(fromX: Int, toX: Int, centreY: Int, amplitude: Int, radius: Int = 4, darkness: Float = 0.25f) {
            for ((x, cy) in centreLine(fromX, toX, centreY, amplitude)) {
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        if (dx * dx + dy * dy > radius * radius) continue
                        val px = x + dx
                        val py = cy + dy
                        if (px !in 0 until width || py !in 0 until height) continue
                        val i = py * width + px
                        if (!ink[i]) {
                            ink[i] = true
                            gray[i] = (gray[i] * darkness).roundToInt()
                        }
                    }
                }
            }
        }

        fun centreLine(fromX: Int, toX: Int, centreY: Int, amplitude: Int): List<Pair<Int, Int>> =
            (fromX..toX).map { x ->
                x to (centreY + amplitude * sin((x - fromX).toDouble() / (toX - fromX) * 2 * PI)).roundToInt()
            }

        fun square(left: Int, top: Int, size: Int, value: Int) {
            for (y in top until top + size) for (x in left until left + size) {
                gray[y * width + x] = value
                ink[y * width + x] = true
            }
        }

        fun clean(strength: Float = 0.5f): InkMask {
            val image = GrayImage(width, height, gray.copyOf())
            return SignatureCleaner.clean(image, SignatureCleaner.integralOf(image), strength)
        }

        /** Pixels marked as ink that are more than a few pixels from any real ink. */
        fun strayInk(mask: InkMask, tolerance: Int = 6): Int {
            var stray = 0
            for (y in 0 until height) for (x in 0 until width) {
                if (mask.at(x, y) > 0 && !nearInk(x, y, tolerance)) stray++
            }
            return stray
        }

        private fun nearInk(x: Int, y: Int, distance: Int): Boolean {
            for (dy in -distance..distance) {
                val ny = y + dy
                if (ny !in 0 until height) continue
                for (dx in -distance..distance) {
                    val nx = x + dx
                    if (nx in 0 until width && ink[ny * width + nx]) return true
                }
            }
            return false
        }
    }
}
