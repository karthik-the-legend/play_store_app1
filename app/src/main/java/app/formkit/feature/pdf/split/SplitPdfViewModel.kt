package app.formkit.feature.pdf.split

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import app.formkit.core.pdf.PageRange
import app.formkit.core.pdf.PageRanges
import app.formkit.core.pdf.RangeParse
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

enum class SplitMode { Ranges, Pick }

@Serializable
data class SplitOutput(val path: String, val name: String, val pageCount: Int, val sizeBytes: Long)

@Serializable
data class SplitSession(
    val pdf: PickedPdf? = null,
    val mode: SplitMode = SplitMode.Ranges,
    val rangeText: String = "",
    /** Zero-based page indexes picked in [SplitMode.Pick]. */
    val selected: List<Int> = emptyList(),
    val outputs: List<SplitOutput> = emptyList(),
    val saved: List<SavedFile> = emptyList(),
) {
    fun parsedRanges(): RangeParse? = pdf?.takeIf { it.isReady }?.let { PageRanges.parse(rangeText, it.pageCount) }

    val canSplit: Boolean
        get() = pdf?.isReady == true && when (mode) {
            SplitMode.Ranges -> parsedRanges() is RangeParse.Valid
            SplitMode.Pick -> selected.isNotEmpty()
        }
}

/** "Statement_pages_2-4.pdf" */
fun splitFileName(pdfName: String, pages: String): String {
    val compact = pages.replace(", ", "_").replace(' ', '_').take(MAX_PAGES_LABEL)
    return "${PdfNames.baseName(pdfName)}_pages_$compact.pdf"
}

private const val MAX_PAGES_LABEL = 40

@HiltViewModel
class SplitPdfViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    deps: PdfToolDeps,
) : PdfToolViewModel<SplitSession>(savedStateHandle, SplitSession.serializer(), SplitSession(), deps) {

    override val workspacePrefix = "split"

    override fun pdfsIn(state: SplitSession): List<PickedPdf> = listOfNotNull(state.pdf)

    override fun replacePdf(state: SplitSession, pdf: PickedPdf): SplitSession =
        if (state.pdf?.id == pdf.id) state.copy(pdf = pdf, selected = emptyList()) else state

    fun pickPdf(uri: Uri) = importPdfs(listOf(uri)) { state, imported ->
        state.copy(pdf = imported.first(), rangeText = "", selected = emptyList(), outputs = emptyList(), saved = emptyList())
    }

    fun chooseMode(mode: SplitMode) = updateSession { it.copy(mode = mode) }

    fun typeRanges(text: String) = updateSession { it.copy(rangeText = text.take(MAX_RANGE_TEXT)) }

    fun togglePage(index: Int) = updateSession { state ->
        state.copy(selected = if (index in state.selected) state.selected - index else (state.selected + index).sorted())
    }

    fun selectAll() = updateSession { state -> state.copy(selected = (0 until (state.pdf?.pageCount ?: 0)).toList()) }

    fun clearSelection() = updateSession { it.copy(selected = emptyList()) }

    fun split() {
        val state = session.value
        if (!state.canSplit) return
        val pdf = checkNotNull(state.pdf)
        val source = File(checkNotNull(pdf.readablePath))
        val jobs: List<Pair<List<Int>, String>> = when (state.mode) {
            SplitMode.Ranges -> (state.parsedRanges() as RangeParse.Valid).ranges.map { range: PageRange ->
                PageRanges.pageIndices(listOf(range)) to splitFileName(pdf.displayName, range.toString())
            }
            SplitMode.Pick -> listOf(state.selected to splitFileName(pdf.displayName, PageRanges.format(state.selected)))
        }
        runWork {
            val workspace = workspace()
            val folder = File(workspace, "split-${System.currentTimeMillis()}")
            val previous = session.value.outputs.map { it.path }
            val outputs = jobs.mapIndexed { index, (pages, name) ->
                setProgress(WorkProgress(ProgressStage.Pages, index + 1, jobs.size))
                val output = File(folder, "$index.pdf")
                deps.documents.extractPages(source, pages, output)
                SplitOutput(output.path, name, pages.size, withContext(deps.ioDispatcher) { output.length() })
            }
            withContext(deps.ioDispatcher) { previous.forEach { File(it).delete() } }
            updateSession { it.copy(outputs = outputs, saved = emptyList()) }
        }
    }

    fun saveAll() {
        val state = session.value
        if (state.outputs.isEmpty() || state.saved.size == state.outputs.size) return
        runSave {
            val saved = state.outputs.map { output ->
                val exported = deps.exports.saveDocument(File(output.path), output.name, PDF_MIME, size = null)
                SavedFile(exported.uri.toString(), exported.displayName)
            }
            updateSession { it.copy(saved = saved) }
            send(PdfEvent.Saved(saved.first().displayName, count = saved.size, inDocuments = true))
        }
    }

    fun shareAll() {
        val state = session.value
        shareOutputs(state.outputs.map { File(it.path) to it.name }, state.saved, PDF_MIME)
    }

    fun backToOptions() = updateSession { it.copy(outputs = emptyList(), saved = emptyList()) }

    private companion object {
        const val MAX_RANGE_TEXT = 200
    }
}
