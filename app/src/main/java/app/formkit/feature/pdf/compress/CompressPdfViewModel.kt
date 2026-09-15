package app.formkit.feature.pdf.compress

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import app.formkit.core.imaging.OptionsError
import app.formkit.core.imaging.TargetInput
import app.formkit.core.pdf.CompressOutcome
import app.formkit.core.pdf.CompressProgress
import app.formkit.core.pdf.PdfCompressor
import app.formkit.feature.pdf.common.PDF_MIME
import app.formkit.feature.pdf.common.PdfEvent
import app.formkit.feature.pdf.common.PdfNames
import app.formkit.feature.pdf.common.PdfProblem
import app.formkit.feature.pdf.common.PdfToolDeps
import app.formkit.feature.pdf.common.PdfToolViewModel
import app.formkit.feature.pdf.common.PickedPdf
import app.formkit.feature.pdf.common.ProgressStage
import app.formkit.feature.pdf.common.SavedFile
import app.formkit.feature.pdf.common.WorkProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import javax.inject.Inject
import kotlin.math.max

enum class CompressPreset(val kilobytes: Int?) {
    Kb100(100),
    Kb200(200),
    Kb500(500),
    Mb1(1000),
    Mb2(2000),
    Custom(null),
}

@Serializable
data class CompressResult(
    val path: String,
    val sizeBytes: Long,
    val originalBytes: Long,
    val pageCount: Int,
    val maxBytes: Long,
    val saved: SavedFile? = null,
)

@Serializable
data class CompressSession(
    val pdf: PickedPdf? = null,
    val preset: CompressPreset = CompressPreset.Kb500,
    val customKb: String = "",
    val result: CompressResult? = null,
) {
    /** The chosen limit in bytes, or null after recording in [errors] why the typed one can't be used. */
    fun maxBytes(errors: MutableSet<OptionsError> = mutableSetOf()): Long? =
        (preset.kilobytes ?: TargetInput.kilobytes(customKb, errors))?.let { it * TargetInput.BYTES_PER_KB }
}

/** "Statement_184KB.pdf" */
fun compressedFileName(pdfName: String, sizeBytes: Long): String =
    "${PdfNames.baseName(pdfName)}_${max(1L, sizeBytes / TargetInput.BYTES_PER_KB)}KB.pdf"

@HiltViewModel
class CompressPdfViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    deps: PdfToolDeps,
    private val compressor: PdfCompressor,
) : PdfToolViewModel<CompressSession>(savedStateHandle, CompressSession.serializer(), CompressSession(), deps) {

    override val workspacePrefix = "compress"

    override fun pdfsIn(state: CompressSession): List<PickedPdf> = listOfNotNull(state.pdf)

    override fun replacePdf(state: CompressSession, pdf: PickedPdf): CompressSession =
        if (state.pdf?.id == pdf.id) state.copy(pdf = pdf) else state

    fun pickPdf(uri: Uri) = importPdfs(listOf(uri)) { state, imported -> state.copy(pdf = imported.first(), result = null) }

    fun choosePreset(preset: CompressPreset) = updateSession { it.copy(preset = preset) }

    fun typeCustomKb(text: String) = updateSession { it.copy(customKb = text.filter(Char::isDigit).take(TargetInput.MAX_DIGITS)) }

    fun compress() {
        val state = session.value
        val pdf = state.pdf?.takeIf { it.isReady } ?: return
        val readable = File(pdf.readablePath ?: return)
        val maxBytes = state.maxBytes() ?: return
        if (!pdf.wasProtected && pdf.sizeBytes <= maxBytes) {
            showProblem(PdfProblem.AlreadySmall(pdf.sizeBytes, maxBytes))
            return
        }
        runWork {
            val workspace = workspace()
            val previous = session.value.result?.path
            val output = File(workspace, "compressed-${System.currentTimeMillis()}.pdf")
            val pagesDir = File(workspace, "pages")
            try {
                val readableBytes = withContext(deps.ioDispatcher) { readable.length() }
                val sizeBytes = if (readableBytes <= maxBytes) {
                    // A protected PDF that already fits only needs its password taken off:
                    // upload portals usually refuse protected files.
                    withContext(deps.ioDispatcher) { readable.copyTo(output, overwrite = true).length() }
                } else {
                    when (val outcome = compressor.compress(readable, maxBytes, pagesDir, output, ::report)) {
                        is CompressOutcome.Done -> outcome.sizeBytes
                        is CompressOutcome.TooLarge -> {
                            showProblem(PdfProblem.TooLarge(outcome.smallestBytes, maxBytes))
                            return@runWork
                        }
                    }
                }
                previous?.let { path -> withContext(deps.ioDispatcher) { File(path).delete() } }
                updateSession {
                    it.copy(result = CompressResult(output.path, sizeBytes, pdf.sizeBytes, pdf.pageCount, maxBytes))
                }
            } finally {
                withContext(NonCancellable + deps.ioDispatcher) { pagesDir.deleteRecursively() }
            }
        }
    }

    fun save() {
        val state = session.value
        val result = state.result ?: return
        if (result.saved != null) return
        val name = compressedFileName(state.pdf?.displayName.orEmpty(), result.sizeBytes)
        runSave {
            val exported = deps.exports.saveDocument(File(result.path), name, PDF_MIME, size = null, maxBytes = result.maxBytes)
            updateSession { it.copy(result = it.result?.copy(saved = SavedFile(exported.uri.toString(), exported.displayName))) }
            send(PdfEvent.Saved(exported.displayName, count = 1, inDocuments = true))
        }
    }

    fun share() {
        val state = session.value
        val result = state.result ?: return
        val name = compressedFileName(state.pdf?.displayName.orEmpty(), result.sizeBytes)
        shareOutputs(listOf(File(result.path) to name), listOfNotNull(result.saved), PDF_MIME)
    }

    fun backToOptions() = updateSession { it.copy(result = null) }

    private fun report(progress: CompressProgress) {
        val stage = when (progress.stage) {
            CompressProgress.Stage.Analysing -> ProgressStage.Analysing
            CompressProgress.Stage.Compressing -> ProgressStage.Pages
            CompressProgress.Stage.Writing -> ProgressStage.Writing
        }
        setProgress(WorkProgress(stage, progress.page, progress.pageCount))
    }
}
