package app.formkit.core.history

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

class ExportHistoryTest {

    private fun record(id: String, createdAt: Long = 0) = ExportRecord(
        id = id,
        uri = "content://media/external/images/media/$id",
        displayName = "IMG_$id.jpg",
        mimeType = "image/jpeg",
        sizeBytes = 48_000,
        width = 413,
        height = 531,
        createdAtMillis = createdAt,
    )

    @Test
    fun `history round-trips through the serializer`() = runTest {
        val history = ExportHistory(listOf(record("1"), record("2")))
        val output = ByteArrayOutputStream()

        ExportHistorySerializer.writeTo(history, output)

        assertEquals(history, ExportHistorySerializer.readFrom(output.toByteArray().inputStream()))
    }

    @Test
    fun `fields added by a newer version are ignored`() = runTest {
        val json = """{"records":[{"id":"1","uri":"content://x","displayName":"a.jpg","mimeType":"image/jpeg",
            "sizeBytes":10,"createdAtMillis":5,"thumbnailPath":"/cache/t.jpg"}],"version":3}"""

        val history = ExportHistorySerializer.readFrom(json.byteInputStream())

        assertEquals("a.jpg", history.records.single().displayName)
    }

    @Test
    fun `a damaged file is reported as corruption`() = runTest {
        try {
            ExportHistorySerializer.readFrom("{\"records\": [".byteInputStream())
            fail("Expected CorruptionException")
        } catch (_: CorruptionException) {
        }
    }

    @Test
    fun `new exports go first and the list is capped`() = runTest {
        val repository = ExportHistoryRepository(InMemoryDataStore(ExportHistory()))

        repeat(ExportHistoryRepository.MAX_RECORDS + 5) { repository.add(record("$it")) }

        val records = repository.records.first()
        assertEquals(ExportHistoryRepository.MAX_RECORDS, records.size)
        assertEquals("${ExportHistoryRepository.MAX_RECORDS + 4}", records.first().id)
    }

    @Test
    fun `records can be removed`() = runTest {
        val repository = ExportHistoryRepository(InMemoryDataStore(ExportHistory()))
        listOf("a", "b", "c").forEach { repository.add(record(it)) }

        repository.remove("b")
        repository.removeAll(setOf("c", "missing"))

        assertEquals(listOf("a"), repository.records.first().map { it.id })
    }
}

private class InMemoryDataStore<T>(initial: T) : DataStore<T> {
    private val state = MutableStateFlow(initial)
    private val mutex = Mutex()

    override val data: Flow<T> = state

    override suspend fun updateData(transform: suspend (t: T) -> T): T =
        mutex.withLock { transform(state.value).also { state.value = it } }
}
