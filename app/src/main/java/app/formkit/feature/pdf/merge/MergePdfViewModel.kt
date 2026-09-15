package app.formkit.feature.pdf.merge

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import app.formkit.feature.pdf.common.PDF_MIME
import app.formkit.feature.pdf.common.PdfEvent
import app.formkit.feature.pdf.common.PdfNames
import app.formkit.feature.pdf.common.PdfToolDeps
import app.formkit.feature.pdf.common.PdfToolViewModel
import app.formkit.feature.pdf.common.PickedPdf
import app.formkit.feature.pdf.common.ProgressStage
import app.formkit.feature.pdf.common.SavedFile
import app.formkit.feature.pdf.common.WorkProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import javax.inject.Inject

@Serializable
data class MergeResult(
    val path: String,
    val sizeBytes: Long,
    val pageCount: Int,
    val fileCount: Int,
    val saved: SavedFile? = null,
)

@Serializable
data class MergeSession(
    val pdfs: List<PickedPdf> = emptyList(),
    val result: MergeResult? = null,
) {
    val canMerge: Boolean get() = pdfs.size >= MIN_FILES && pdfs.all { it.isReady }
    val totalPages: Int get() = pdfs.sumOf { it.pageCount }

    companion object {
        const val MIN_FILES = 2
    }
}

/** "Offer letter_merged.pdf", named after the first file. */
fun mergedFileName(firstName: String): String = "${PdfNames.baseName(firstName)}_merged.pdf"

@HiltViewModel
class MergePdfViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    deps: PdfToolDeps,
) : PdfToolViewModel<MergeSession>(savedStateHandle, MergeSession.serializer(), MergeSession(), deps) {

    override val workspacePrefix = "merge"

    override fun pdfsIn(state: MergeSession): List<PickedPdf> = state.pdfs

    override fun replacePdf(state: MergeSession, pdf: PickedPdf): MergeSession =
        state.copy(pdfs = state.pdfs.map { if (it.id == pdf.id) pdf else it })

    fun addPdfs(uris: List<Uri>) = importPdfs(uris) { state, imported -> state.copy(pdfs = state.pdfs + imported, result = null) }

    fun remove(id: String) = updateSession { state -> state.copy(pdfs = state.pdfs.filterNot { it.id == id }) }

    fun move(from: Int, to: Int) = updateSession { state ->
        if (from !in state.pdfs.indices || to !in state.pdfs.indices) return@updateSession state
        state.copy(pdfs = state.pdfs.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun merge() {
        val state = session.value
        if (!state.canMerge) return
        val sources = state.pdfs.map { File(checkNotNull(it.readablePath)) }
        runWork {
            val workspace = workspace()
            val previous = session.value.result?.path
            val output = File(workspace, "merged-${System.currentTimeMillis()}.pdf")
            setProgress(WorkProgress(ProgressStage.Writing, sources.size, sources.size))
            deps.documents.merge(sources, output)
            val sizeBytes = withContext(deps.ioDispatcher) {
                previous?.let { File(it).delete() }
                output.length()
            }
            updateSession { it.copy(result = MergeResult(output.path, sizeBytes, state.totalPages, sources.size)) }
        }
    }

    fun save() {
        val state = session.value
        val result = state.result ?: return
        if (result.saved != null) return
        val name = mergedFileName(state.pdfs.firstOrNull()?.displayName.orEmpty())
        runSave {
            val exported = deps.exports.saveDocument(File(result.path), name, PDF_MIME, size = null)
            updateSession { it.copy(result = it.result?.copy(saved = SavedFile(exported.uri.toString(), exported.displayName))) }
            send(PdfEvent.Saved(exported.displayName, count = 1, inDocuments = true))
        }
    }

    fun share() {
        val state = session.value
        val result = state.result ?: return
        shareOutputs(listOf(File(result.path) to mergedFileName(state.pdfs.firstOrNull()?.displayName.orEmpty())), listOfNotNull(result.saved), PDF_MIME)
    }

    fun backToList() = updateSession { it.copy(result = null) }
}
