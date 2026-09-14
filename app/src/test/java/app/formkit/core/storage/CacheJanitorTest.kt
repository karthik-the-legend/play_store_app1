package app.formkit.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CacheJanitorTest {

    @get:Rule val tmp = TemporaryFolder()

    private val day = CacheJanitor.STALE_AFTER_MILLIS
    private val now = 30 * day

    private fun janitor() = CacheJanitor(tmp.root) { now }

    private fun file(path: String, bytes: Int, modified: Long): File =
        File(tmp.root, path).apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(bytes))
            setLastModified(modified)
        }

    @Test
    fun `prune removes files older than the max age and keeps newer ones`() {
        val old = file("old.jpg", 10, now - day - 1)
        val recent = file("recent.jpg", 10, now - 60_000)

        assertEquals(1, janitor().pruneOlderThan(day))
        assertFalse(old.exists())
        assertTrue(recent.exists())
    }

    @Test
    fun `prune keeps a directory when anything inside it is recent`() {
        val stale = file("job/source.jpg", 10, now - 3 * day)
        file("job/preview.jpg", 10, now - 60_000)
        File(tmp.root, "job").setLastModified(now - 3 * day)

        assertEquals(0, janitor().pruneOlderThan(day))
        assertTrue(stale.exists())
    }

    @Test
    fun `prune removes a directory when everything inside it is stale`() {
        file("job/a.jpg", 10, now - 2 * day)
        file("job/nested/b.jpg", 10, now - 2 * day)
        File(tmp.root, "job/nested").setLastModified(now - 2 * day)
        File(tmp.root, "job").setLastModified(now - 2 * day)

        assertEquals(1, janitor().pruneOlderThan(day))
        assertFalse(File(tmp.root, "job").exists())
    }

    @Test
    fun `size counts files in nested directories`() {
        file("a.jpg", 100, now)
        file("job/b.jpg", 50, now)

        assertEquals(150L, janitor().sizeBytes())
    }

    @Test
    fun `clear empties the cache and reports the bytes freed`() {
        file("a.jpg", 100, now)
        file("job/b.jpg", 50, now)

        assertEquals(150L, janitor().clear())
        assertEquals(0, tmp.root.listFiles()!!.size)
    }

    @Test
    fun `a missing cache directory behaves as empty`() {
        val janitor = CacheJanitor(File(tmp.root, "absent")) { now }

        assertEquals(0L, janitor.sizeBytes())
        assertEquals(0, janitor.pruneOlderThan(day))
        assertEquals(0L, janitor.clear())
    }
}
