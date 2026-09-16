package app.formkit.feature.scan

import app.formkit.core.imaging.OptionsError
import app.formkit.core.imaging.scan.PageCorners
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanModelsTest {

    private fun sessionWithPages(count: Int) = ScanSession(pages = (1..count).map { page(it) })

    private fun page(number: Int) = ScanPage(
        id = "page-$number",
        photoPath = "/tmp/page-$number.jpg",
        photoWidth = 3000,
        photoHeight = 4000,
        corners = PageCorners.WholePhoto,
    )

    @Test
    fun `a preset limit is that many thousand bytes`() {
        assertEquals(500_000L, sessionWithPages(1).copy(limit = ScanSizeLimit.Kb500).maxBytes())
        assertEquals(2_000_000L, sessionWithPages(1).copy(limit = ScanSizeLimit.Mb2).maxBytes())
    }

    @Test
    fun `no limit means no limit, not an error`() {
        val session = sessionWithPages(1).copy(limit = ScanSizeLimit.None)
        val errors = mutableSetOf<OptionsError>()

        assertNull(session.maxBytes(errors))
        assertTrue(errors.isEmpty())
        assertTrue(session.canCreate)
    }

    @Test
    fun `a typed limit is used once it's in range`() {
        val session = sessionWithPages(2).copy(limit = ScanSizeLimit.Custom, customKb = "250")

        assertEquals(250_000L, session.maxBytes())
        assertTrue(session.canCreate)
    }

    @Test
    fun `a typed limit that's too small blocks the PDF and says why`() {
        val session = sessionWithPages(2).copy(limit = ScanSizeLimit.Custom, customKb = "1")
        val errors = mutableSetOf<OptionsError>()

        assertNull(session.maxBytes(errors))
        assertTrue(OptionsError.CustomSizeTooSmall in errors)
        assertFalse(session.canCreate)
    }

    @Test
    fun `there's nothing to make without pages`() {
        assertFalse(ScanSession().canCreate)
    }

    @Test
    fun `pages stop being addable at the limit`() {
        assertTrue(sessionWithPages(ScanSession.MAX_PAGES - 1).canAddMore)
        assertFalse(sessionWithPages(ScanSession.MAX_PAGES).canAddMore)
    }

    @Test
    fun `the page being edited is the one the session points at`() {
        val session = sessionWithPages(3).copy(editingId = "page-2")

        assertEquals("page-2", session.editing?.id)
        assertNull(session.copy(editingId = null).editing)
        assertNull(session.copy(editingId = "gone").editing)
    }

    @Test
    fun `the file is named after how many pages it has`() {
        assertEquals("Scan_3_pages.pdf", scanFileName(3))
    }
}
