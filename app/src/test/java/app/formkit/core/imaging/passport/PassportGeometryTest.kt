package app.formkit.core.imaging.passport

import app.formkit.core.imaging.PixelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PassportGeometryTest {

    @Test
    fun `presets convert to exact pixel sizes at 300 DPI`() {
        assertEquals(PixelSize(413, 531), PassportPreset.India.pixelSize())
        assertEquals(PixelSize(413, 531), PassportPreset.Schengen.pixelSize())
        assertEquals(PixelSize(600, 600), PassportPreset.UnitedStates.pixelSize())
        assertEquals(PixelSize(602, 602), PassportPreset.Square51.pixelSize())
        assertEquals(PixelSize(276, 354), PassportPreset.India.pixelSize(dpi = 200))
    }

    @Test
    fun `a placement maps between source and frame both ways`() {
        val frame = PixelSize(413, 531)
        val placement = Placement(centerX = 1000f, centerY = 800f, visibleHeight = 1062f)

        assertEquals(0.5f, placement.scaleFor(frame), DELTA)
        val (frameX, frameY) = placement.toFrame(1100f, 900f, frame)
        assertEquals(206.5f + 50f, frameX, DELTA)
        assertEquals(265.5f + 50f, frameY, DELTA)
        val (sourceX, sourceY) = placement.toSource(frameX, frameY, frame)
        assertEquals(1100f, sourceX, DELTA)
        assertEquals(900f, sourceY, DELTA)
    }

    @Test
    fun `dragging moves the photo with the finger`() {
        val frame = PixelSize(400, 400)
        val placement = Placement(500f, 500f, visibleHeight = 800f)

        val dragged = placement.dragged(dx = 100f, dy = 0f, frame = frame)

        // Scale 0.5: 100 frame pixels is 200 source pixels, and dragging right reveals what's left.
        assertEquals(300f, dragged.centerX, DELTA)
        assertEquals(500f, dragged.centerY, DELTA)
    }

    @Test
    fun `zooming keeps the point under the fingers still`() {
        val frame = PixelSize(400, 400)
        val placement = Placement(500f, 500f, visibleHeight = 800f)
        val (before) = placement.toSource(100f, 100f, frame).let { listOf(it) }

        val zoomed = placement.zoomed(factor = 2f, focusX = 100f, focusY = 100f, frame = frame)

        assertEquals(400f, zoomed.visibleHeight, DELTA)
        val after = zoomed.toSource(100f, 100f, frame)
        assertEquals(before.first, after.first, DELTA)
        assertEquals(before.second, after.second, DELTA)
    }

    @Test
    fun `cover fills the frame with the whole photo`() {
        val landscape = Placement.cover(PixelSize(4000, 3000), PixelSize(413, 531))
        assertEquals(3000f, landscape.visibleHeight, DELTA)

        val portrait = Placement.cover(PixelSize(3000, 4000), PixelSize(600, 600))
        assertEquals(3000f, portrait.visibleHeight, DELTA)
    }

    @Test
    fun `the head is found from a person silhouette`() {
        // A head (ellipse 300 wide, 390 tall, top at y=200), a neck, then wide shoulders.
        val mask = silhouette(width = 1200, height = 1600, headTop = 200, headWidth = 300, headHeight = 390)

        val head = HeadFraming.measureHead(mask)!!

        assertEquals(200f, head.crownY, 2f)
        assertTrue("chin ${head.chinY} should be near 590", abs(head.chinY - 590f) <= 40f)
        assertEquals(600f, head.centerX, 3f)
    }

    @Test
    fun `a short-haired portrait puts the chin below the jaw line`() {
        // Silhouette widths measured every 20 rows from a real short-haired portrait (crown at
        // y=130). The silhouette is narrowest at the jaw (y≈510); the chin was at about y=550.
        val profile = intArrayOf(
            23, 139, 203, 246, 268, 287, 302, 309, 314, 317, 319, 320, 311, 301, 285, 256,
            243, 230, 219, 211, 223, 243, 420, 600, 800, 1000, 1100, 1150,
        )
        val mask = profileMask(width = 1280, height = 1600, top = 130, step = 20, widths = profile)

        val head = HeadFraming.measureHead(mask)!!

        assertEquals(130f, head.crownY, 2f)
        assertTrue("chin ${head.chinY} should be near 550", abs(head.chinY - 550f) <= 25f)
    }

    @Test
    fun `an empty mask has no head`() {
        assertNull(HeadFraming.measureHead(ForegroundMask(100, 100, ByteArray(100 * 100))))
    }

    @Test
    fun `framing puts crown and chin on the guide lines`() {
        val head = HeadMeasurement(crownY = 200f, chinY = 600f, centerX = 640f)
        val frame = PixelSize(413, 531)
        val guide = PassportPreset.India.guide

        val placement = HeadFraming.placementFor(head, guide)

        assertEquals(guide.crown * frame.height, placement.toFrame(640f, 200f, frame).second, 0.5f)
        assertEquals(guide.chin * frame.height, placement.toFrame(640f, 600f, frame).second, 0.5f)
        assertEquals(frame.width / 2f, placement.toFrame(640f, 400f, frame).first, 0.5f)
    }

    private fun silhouette(width: Int, height: Int, headTop: Int, headWidth: Int, headHeight: Int): ForegroundMask {
        val alpha = ByteArray(width * height)
        val cx = width / 2f
        val cy = headTop + headHeight / 2f
        val rx = headWidth / 2f
        val ry = headHeight / 2f
        fun fill(x: Int, y: Int) {
            if (x in 0 until width && y in 0 until height) alpha[y * width + x] = 255.toByte()
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val nx = (x - cx) / rx
                val ny = (y - cy) / ry
                if (nx * nx + ny * ny <= 1f) fill(x, y)
            }
        }
        val neckTop = headTop + headHeight - 20
        val neckBottom = headTop + headHeight + 60
        for (y in neckTop until neckBottom) for (x in (cx - 70).toInt()..(cx + 70).toInt()) fill(x, y)
        for (y in neckBottom until height) {
            val spread = ((y - neckBottom) * 2.5f + 150f).coerceAtMost(width / 2f)
            for (x in (cx - spread).toInt()..(cx + spread).toInt()) fill(x, y)
        }
        return ForegroundMask(width, height, alpha)
    }

    /** A centred silhouette whose row widths are interpolated from [widths], one every [step] rows from [top]. */
    private fun profileMask(width: Int, height: Int, top: Int, step: Int, widths: IntArray): ForegroundMask {
        val alpha = ByteArray(width * height)
        for (y in top until height) {
            val position = (y - top).toFloat() / step
            val index = position.toInt().coerceAtMost(widths.size - 1)
            val next = (index + 1).coerceAtMost(widths.size - 1)
            val rowWidth = (widths[index] + (widths[next] - widths[index]) * (position - index).coerceIn(0f, 1f)).toInt()
            val left = (width - rowWidth) / 2
            for (x in left.coerceAtLeast(0) until (left + rowWidth).coerceAtMost(width)) alpha[y * width + x] = 255.toByte()
        }
        return ForegroundMask(width, height, alpha)
    }

    private companion object {
        const val DELTA = 0.01f
    }
}
