package app.formkit.core.history

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** One file the user saved. Stays on the phone; nothing here is ever uploaded. */
@Serializable
data class ExportRecord(
    val id: String,
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int? = null,
    val height: Int? = null,
    val createdAtMillis: Long,
)

@Serializable
data class ExportHistory(val records: List<ExportRecord> = emptyList())

object ExportHistorySerializer : Serializer<ExportHistory> {

    private val json = Json { ignoreUnknownKeys = true }

    override val defaultValue: ExportHistory = ExportHistory()

    override suspend fun readFrom(input: InputStream): ExportHistory = try {
        json.decodeFromString(ExportHistory.serializer(), input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("Export history is unreadable", e)
    } catch (e: IllegalArgumentException) {
        throw CorruptionException("Export history is unreadable", e)
    }

    override suspend fun writeTo(t: ExportHistory, output: OutputStream) {
        output.write(json.encodeToString(ExportHistory.serializer(), t).encodeToByteArray())
    }
}

@Singleton
class ExportHistoryRepository @Inject constructor(
    private val dataStore: DataStore<ExportHistory>,
) {

    /** Newest first. */
    val records: Flow<List<ExportRecord>> = dataStore.data
        .catch { e -> if (e is IOException) emit(ExportHistory()) else throw e }
        .map { it.records }

    suspend fun add(record: ExportRecord) {
        dataStore.updateData { history ->
            val others = history.records.filterNot { it.id == record.id }
            history.copy(records = (listOf(record) + others).take(MAX_RECORDS))
        }
    }

    suspend fun remove(id: String) = removeAll(setOf(id))

    suspend fun removeAll(ids: Set<String>) {
        if (ids.isEmpty()) return
        dataStore.updateData { history -> history.copy(records = history.records.filterNot { it.id in ids }) }
    }

    companion object {
        const val MAX_RECORDS = 100
    }
}
