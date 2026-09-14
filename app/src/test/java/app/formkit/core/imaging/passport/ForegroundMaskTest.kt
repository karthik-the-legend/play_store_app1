package app.formkit.core.imaging.passport

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundMaskTest {

    @Test
    fun `confidence becomes a sharper but still smooth mask`() {
        val mask = ForegroundMask.fromConfidence(5, 1, floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f))

        assertEquals(0, mask.at(0, 0))
        assertEquals(0, mask.at(1, 0))
        assertEquals(128, mask.at(2, 0))
        assertEquals(255, mask.at(3, 0))
        assertEquals(255, mask.at(4, 0))
    }

    @Test
    fun `erasing clears the middle of the brush and fades at its edge`() {
        val mask = solid(100, 100, 255)

        val dirty = MaskBrush.apply(mask, BrushStroke(BrushMode.Erase, radius = 10f, points = listOf(50f, 50f)))

        assertEquals(0, mask.at(50, 50))
        assertEquals("inside the solid core", 0, mask.at(54, 50))
        val edge = mask.at(58, 50)
        assertTrue("edge $edge should be partly erased", edge in 1..254)
        assertEquals("outside the brush", 255, mask.at(65, 50))
        assertTrue(dirty.left <= 40 && dirty.right >= 60 && dirty.top <= 40 && dirty.bottom >= 60)
    }

    @Test
    fun `restoring brings back what the model missed`() {
        val mask = solid(100, 100, 0)

        MaskBrush.apply(mask, BrushStroke(BrushMode.Restore, radius = 8f, points = listOf(20f, 20f, 80f, 20f)))

        for (x in 20..80 step 5) assertEquals("restored at $x", 255, mask.at(x, 20))
        assertEquals(0, mask.at(50, 40))
    }

    @Test
    fun `a fast stroke stays continuous between far-apart points`() {
        val mask = solid(200, 50, 255)

        MaskBrush.apply(mask, BrushStroke(BrushMode.Erase, radius = 6f, points = listOf(10f, 25f, 190f, 25f)))

        for (x in 10..190) assertEquals("gap at $x", 0, mask.at(x, 25))
    }

    @Test
    fun `undo replays everything but the last stroke`() {
        val base = solid(60, 60, 255)
        val first = BrushStroke(BrushMode.Erase, 5f, listOf(15f, 15f))
        val second = BrushStroke(BrushMode.Erase, 5f, listOf(45f, 45f))

        val edits = MaskEdits() + first + second
        val undone = edits.undo().applyTo(base)

        assertEquals(0, undone.at(15, 15))
        assertEquals(255, undone.at(45, 45))
        assertEquals("the model's mask itself is untouched", 255, base.at(15, 15))
        assertTrue(edits.canUndo)
        assertFalse(MaskEdits().canUndo)
    }

    @Test
    fun `edits survive being saved and restored`() {
        val edits = MaskEdits(listOf(BrushStroke(BrushMode.Restore, 12.5f, listOf(1f, 2f, 3f, 4f))))

        val json = Json.encodeToString(MaskEdits.serializer(), edits)

        assertEquals(edits, Json.decodeFromString(MaskEdits.serializer(), json))
    }

    private fun solid(width: Int, height: Int, value: Int) =
        ForegroundMask(width, height, ByteArray(width * height) { value.toByte() })
}
