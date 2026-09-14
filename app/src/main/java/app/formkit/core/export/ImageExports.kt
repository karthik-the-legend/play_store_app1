package app.formkit.core.export

import android.net.Uri
import app.formkit.core.di.IoDispatcher
import app.formkit.core.history.ExportHistoryRepository
import app.formkit.core.history.ExportRecord
import app.formkit.core.imaging.OutputFormat
import app.formkit.core.imaging.PixelSize
import app.formkit.core.storage.ExportedFile
import app.formkit.core.storage.FileExporter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Saving and sharing a finished image, the same way from every tool. */
@Singleton
class ImageExports @Inject constructor(
    private val exporter: FileExporter,
    private val history: ExportHistoryRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /** Saves to Pictures/FormKit, checks the size on disk, and adds it to Recent files. */
    suspend fun save(file: File, displayName: String, format: OutputFormat, maxBytes: Long?, size: PixelSize): ExportedFile {
        val exported = exporter.saveImage(file, displayName, format.mimeType, maxBytes)
        history.add(
            ExportRecord(
                id = UUID.randomUUID().toString(),
                uri = exported.uri.toString(),
                displayName = exported.displayName,
                mimeType = format.mimeType,
                sizeBytes = exported.sizeBytes,
                width = size.width,
                height = size.height,
                createdAtMillis = System.currentTimeMillis(),
            ),
        )
        return exported
    }

    /** A copy of [file] under a readable name, so a file can be shared before it's saved. */
    suspend fun shareableCopy(file: File, displayName: String): Uri = withContext(ioDispatcher) {
        val named = File(file.parentFile, "share/$displayName")
        named.parentFile?.mkdirs()
        file.copyTo(named, overwrite = true)
        exporter.shareableUri(named)
    }
}
