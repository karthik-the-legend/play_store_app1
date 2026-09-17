package app.formkit.core.imaging.passport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MaskRefinementTest {

    /** A confidence map that is [inside] within the rectangle and [outside] elsewhere. */
    private fun rectangle(width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int, inside: Float = 1f, outside: Float = 0f) =
        Confidence(width, height, FloatArray(width * height) { i ->
            val x = i % width
            val y = i / width
            if (x in left until right && y in top until bottom) inside else outside
        })

    @Test
    fun `a full-length person gets a closer look at the head and chest`() {
        // A person 100 px wide and 400 px tall, standing in a much larger photo.
        val confidence = rectangle(800, 1000, left = 350, top = 500, right = 450, bottom = 900)

        val box = MaskRefinement.secondPassCrop(confidence)

        assertNotNull(box)
        box!!
        // Tall enough for the chest (0.55 of the height) with a margin, not the whole body.
        assertEquals(275, box.size)
        assertTrue("the head is inside: top ${box.top}", box.top < 500)
        assertTrue("the person's centre is in the middle", abs(box.left + box.size / 2 - 400) <= 1)
        assertTrue(box.left >= 0 && box.top >= 0 && box.left + box.size <= 800 && box.top + box.size <= 1000)
    }

    @Test
    fun `no closer look when the person already fills the photo`() {
        // A square photo the person fills: a crop would be nearly the whole photo again.
        val confidence = rectangle(600, 600, left = 20, top = 20, right = 580, bottom = 600)

        assertNull(MaskRefinement.secondPassCrop(confidence))
    }

    @Test
    fun `no closer look when nobody is there`() {
        assertNull(MaskRefinement.secondPassCrop(Confidence(300, 300, FloatArray(300 * 300))))
    }

    @Test
    fun `the closer answer is used inside the square and blended at its edges`() {
        val base = Confidence(200, 200, FloatArray(200 * 200) { 0.2f })
        val box = CropBox(left = 50, top = 50, size = 100)
        val crop = Confidence(100, 100, FloatArray(100 * 100) { 0.9f })

        val blended = MaskRefinement.blendCrop(base, crop, box)

        fun at(x: Int, y: Int) = blended.values[y * 200 + x]
        assertEquals(0.9f, at(100, 100), 1e-6f)
        assertEquals("right on the square's edge the base still counts", 0.2f, at(50, 100), 1e-6f)
        assertTrue("part way through the band it's a mix", at(52, 100) in 0.21f..0.89f)
        assertEquals("outside the square nothing changes", 0.2f, at(10, 10), 1e-6f)
    }

    @Test
    fun `there's no blending where the square meets the photo's edge`() {
        val base = Confidence(100, 100, FloatArray(100 * 100) { 0f })
        val box = CropBox(left = 0, top = 0, size = 60)
        val crop = Confidence(60, 60, FloatArray(60 * 60) { 1f })

        val blended = MaskRefinement.blendCrop(base, crop, box)

        assertEquals(1f, blended.values[0], 0f)
        assertEquals("but the inner edges still blend", 0f, blended.values[30 * 100 + 59], 0f)
    }

    @Test
    fun `a blocky edge a few pixels out snaps onto the photo's own edge`() {
        val width = 200
        val height = 120
        // The photo: a dark person on the left 100 columns, a bright wall on the right.
        val luma = IntArray(width * height) { i -> if (i % width < 100) 30 else 200 }
        // The model's answer ramps down across a whole cell, centred 3 px out into the wall, the way
        // an upscaled 256 x 256 answer does. A plain cut-off would keep that rim.
        val confidence = Confidence(width, height, FloatArray(width * height) { i ->
            val x = i % width
            ((103 - x) / 6f + 0.5f).coerceIn(0f, 1f)
        })
        assertTrue("without refining, the rim would stay", ForegroundMask.fromConfidence(width, height, confidence.values, 0.5f, 0.7f).at(102, 60) > 128)

        val mask = MaskRefinement.refine(confidence, luma, radius = 8, epsilon = 1e-3f)

        val row = 60
        assertEquals("person side stays", 255, mask.at(90, row))
        assertEquals("wall just past the true edge is cleared", 0, mask.at(102, row))
        assertEquals("the old blocky rim is cleared", 0, mask.at(105, row))
    }

    @Test
    fun `a patch the model half-believed in, away from the person, is dropped`() {
        val width = 300
        val height = 300
        val luma = IntArray(width * height) { 120 }
        val confidence = Confidence(width, height, FloatArray(width * height) { i ->
            val x = i % width
            val y = i / width
            when {
                x in 100 until 200 && y in 100 until 300 -> 1f
                // A small poster in the corner.
                x in 10 until 30 && y in 10 until 30 -> 0.9f
                else -> 0f
            }
        })

        val mask = MaskRefinement.refine(confidence, luma, radius = 4, epsilon = 1e-3f)

        assertEquals(255, mask.at(150, 200))
        assertEquals("the poster is gone", 0, mask.at(20, 20))
    }

    @Test
    fun `nobody in the photo gives an empty mask`() {
        val mask = MaskRefinement.refine(Confidence(50, 40, FloatArray(50 * 40)), IntArray(50 * 40) { 128 })

        assertEquals(0f, mask.coverage(), 0f)
        assertEquals(50, mask.width)
        assertEquals(40, mask.height)
    }

    @Test
    fun `box means average the window and narrow at the borders`() {
        // 1 2 3 4 5 in a single row.
        val mean = Grid.boxMean(floatArrayOf(1f, 2f, 3f, 4f, 5f), 5, 1, 1)

        assertEquals(1.5f, mean[0], 1e-6f)
        assertEquals(2f, mean[1], 1e-6f)
        assertEquals(3f, mean[2], 1e-6f)
        assertEquals(4.5f, mean[4], 1e-6f)
    }

    @Test
    fun `shrinking and growing a flat grid keeps it flat`() {
        val small = Grid.downsample(FloatArray(7 * 5) { 0.25f }, 7, 5, 2)
        assertEquals(4, small.width)
        assertEquals(3, small.height)

        val big = Grid.upsample(small.values, small.width, small.height, 7, 5)

        assertTrue(big.all { abs(it - 0.25f) < 1e-6f })
    }
}
