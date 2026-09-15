package app.formkit.feature.pdf.common

import android.net.Uri
import app.formkit.core.di.IoDispatcher
import app.formkit.core.pdf.PdfDocuments
import app.formkit.core.pdf.PdfOpenResult
import app.formkit.core.pdf.PdfRasterizer
import app.formkit.core.storage.SourceImporter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Brings picked files into a tool's workspace and works out whether each PDF can be used. */
@Singleton
class PdfIntake @Inject constructor(
    private val importer: SourceImporter,
    private val documents: PdfDocuments,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /** Copies the PDF at [uri] into [workspace] and tries to open it without a password. */
    suspend fun importPdf(uri: Uri, workspace: File): PickedPdf {
        val id = UUID.randomUUID().toString()
        val imported = importer.import(uri, File(workspace, "pdf-$id"))
        val picked = PickedPdf(
            id = id,
            originalPath = imported.file.path,
            displayName = imported.displayName,
            sizeBytes = imported.sizeBytes,
            status = PdfStatus.NeedsPassword,
        )
        return open(picked, password = null)
    }

    /** Tries [password] on a protected PDF. A wrong one comes back as [PdfStatus.WrongPassword]. */
    suspend fun unlock(pdf: PickedPdf, password: String): PickedPdf = open(pdf, password)

    suspend fun importImage(uri: Uri, workspace: File): PickedImage {
        val id = UUID.randomUUID().toString()
        val imported = importer.import(uri, File(workspace, "image-$id"))
        return PickedImage(id, imported.file.path, imported.displayName, imported.sizeBytes)
    }

    private suspend fun open(pdf: PickedPdf, password: String?): PickedPdf {
        val original = File(pdf.originalPath)
        val unlocked = File(original.parentFile, UNLOCKED_FILE)
        return when (val result = documents.open(original, password, unlocked)) {
            is PdfOpenResult.Opened ->
                if (canRender(result.readable)) {
                    pdf.copy(
                        status = PdfStatus.Ready,
                        readablePath = result.readable.path,
                        pageCount = result.pageCount,
                        wasProtected = result.wasProtected,
                    )
                } else {
                    pdf.copy(status = PdfStatus.Unreadable)
                }
            PdfOpenResult.NeedsPassword -> pdf.copy(status = PdfStatus.NeedsPassword)
            PdfOpenResult.WrongPassword -> pdf.copy(status = PdfStatus.WrongPassword)
            PdfOpenResult.CertificateProtected -> pdf.copy(status = PdfStatus.CertificateProtected)
            PdfOpenResult.Unreadable -> pdf.copy(status = PdfStatus.Unreadable)
        }
    }

    /** PdfBox can read some files Android's renderer can't; the tools need both. */
    private suspend fun canRender(file: File): Boolean = withContext(ioDispatcher) {
        runCatching { PdfRasterizer(file).use { it.pageCount > 0 } }.getOrDefault(false)
    }

    private companion object {
        const val UNLOCKED_FILE = "unlocked.pdf"
    }
}
