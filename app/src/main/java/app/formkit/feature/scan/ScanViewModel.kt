package app.formkit.feature.scan

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.formkit.core.imaging.AndroidImageCodec
import app.formkit.core.imaging.OutputFormat
import app.formkit.core.imaging.SourceImageDecoder
import app.formkit.core.imaging.scan.PageCorners
import app.formkit.core.imaging.scan.ScanFilter
import app.formkit.core.pdf.CompressOutcome
import app.formkit.core.pdf.PaperSize
import app.formkit.feature.pdf.common.PDF_MIME
import app.formkit.feature.pdf.common.PdfEvent
import app.formkit.feature.pdf.common.PdfProblem
import app.formkit.feature.pdf.common.PdfToolDeps
import app.formkit.feature.pdf.common.PdfToolViewModel
import app.formkit.feature.pdf.common.PickedPdf
import app.formkit.feature.pdf.common.SavedFile
import app.formkit.core.imaging.TargetInput
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlin.math.max

@HiltViewModel
class ScanViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    deps: PdfToolDeps,
    private val processor: ScanPageProcessor,
    private val writer: ScanPdfWriter,
    private val decoder: SourceImageDecoder,
    private val codec: AndroidImageCodec,
) : PdfToolViewModel<ScanSession>(savedStateHandle, ScanSession.serializer(), ScanSession(), deps) {

    override val workspacePrefix = "scan"

    override fun pdfsIn(state: ScanSession): List<PickedPdf> = emptyList()

    override fun replacePdf(state: ScanSession, pdf: PickedPdf): ScanSession = state

    /** Pages whose preview needs redrawing. One render runs at a time, so edits can't pile up. */
    private val stalePreviews = LinkedHashSet<String>()
    private var previewJob: Job? = null

    init {
        viewModelScope.launch {
            // Coming back after process death: the cache may have been cleared underneath us.
            val lost = withContext(deps.ioDispatcher) {
                session.value.pages.filterNot { File(it.photoPath).exists() }.map { it.id }.toSet()
            }
            if (lost.isNotEmpty()) {
                updateSession { state ->
                    state.copy(
                        pages = state.pages.filterNot { it.id in lost },
                        editingId = state.editingId?.takeUnless { it in lost },
                    )
                }
            }
            for (page in session.value.pages) {
                val hasPreview = page.previewPath?.let { path -> withContext(deps.ioDispatcher) { File(path).exists() } } == true
                if (!hasPreview) refreshPreview(page.id)
            }
        }
    }

    // The camera

    fun openCamera() = updateSession { it.copy(cameraOpen = true, editingId = null) }

    fun closeCamera() = updateSession { it.copy(cameraOpen = false) }

    fun cameraFailed() {
        updateSession { it.copy(cameraOpen = false) }
        showProblem(PdfProblem.CameraFailed)
    }

    /** Where the camera should write its next photo. */
    suspend fun newPhotoFile(): File = File(workspace(), "photo-${UUID.randomUUID()}.jpg")

    /** A photo the camera just saved: find the page on it, then open it for checking. */
    fun addCapture(photo: File) {
        if (activity.value.isImporting) return
        activity.update { it.copy(isImporting = true, problem = null) }
        viewModelScope.launch {
            try {
                val page = newPage(photo)
                updateSession { it.copy(pages = it.pages + page, cameraOpen = false, editingId = page.id) }
                refreshPreview(page.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                withContext(NonCancellable + deps.ioDispatcher) { photo.delete() }
                showProblem(PdfProblem.ImportFailed)
            } finally {
                activity.update { it.copy(isImporting = false) }
            }
        }
    }

    fun addPhotos(uris: List<Uri>) {
        val room = max(0, ScanSession.MAX_PAGES - session.value.pages.size)
        if (uris.isEmpty() || room == 0 || activity.value.isImporting) return
        activity.update { it.copy(isImporting = true, problem = null) }
        viewModelScope.launch {
            try {
                val workspace = workspace()
                val added = uris.take(room).map { uri -> newPage(File(deps.intake.importImage(uri, workspace).path)) }
                updateSession { it.copy(pages = it.pages + added, cameraOpen = false, editingId = added.singleOrNull()?.id) }
                added.forEach { refreshPreview(it.id) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                showProblem(PdfProblem.ImportFailed)
            } finally {
                activity.update { it.copy(isImporting = false) }
            }
        }
    }

    private suspend fun newPage(photo: File): ScanPage {
        val size = withContext(deps.ioDispatcher) { decoder.readSize(photo) }
        val found = processor.findPage(photo)?.corners
        return ScanPage(
            id = UUID.randomUUID().toString(),
            photoPath = photo.path,
            photoWidth = size.width,
            photoHeight = size.height,
            corners = found ?: PageCorners.WholePhoto,
            foundCorners = found,
            filter = session.value.filter,
        )
    }

    // Adjusting a page

    fun edit(id: String) = updateSession { it.copy(editingId = id, cameraOpen = false) }

    fun setCorners(corners: PageCorners) = changeEditedPage { it.copy(corners = corners) }

    fun rotatePage() = changeEditedPage {
        it.copy(corners = it.corners.rotatedClockwise(), foundCorners = it.foundCorners?.rotatedClockwise())
    }

    fun resetCorners() = changeEditedPage { it.copy(corners = it.foundCorners ?: PageCorners.WholePhoto) }

    /** The chosen look also becomes the one new pages start with. */
    fun chooseFilter(filter: ScanFilter) {
        updateSession { it.copy(filter = filter) }
        changeEditedPage { it.copy(filter = filter) }
    }

    fun doneEditing(scanNext: Boolean = false) = updateSession { it.copy(editingId = null, cameraOpen = scanNext) }

    private fun changeEditedPage(change: (ScanPage) -> ScanPage) {
        val id = session.value.editingId ?: return
        updateSession { state -> state.copy(pages = state.pages.map { if (it.id == id) change(it) else it }) }
        refreshPreview(id)
    }

    // The list of pages

    fun remove(id: String) {
        val page = session.value.pages.firstOrNull { it.id == id } ?: return
        updateSession { state ->
            state.copy(pages = state.pages.filterNot { it.id == id }, editingId = state.editingId?.takeUnless { it == id })
        }
        deps.appScope.launch(deps.ioDispatcher) {
            File(page.photoPath).delete()
            page.previewPath?.let { File(it).delete() }
        }
    }

    fun move(from: Int, to: Int) = updateSession { state ->
        if (from !in state.pages.indices || to !in state.pages.indices) return@updateSession state
        state.copy(pages = state.pages.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun choosePaper(paper: PaperSize) = updateSession { it.copy(paper = paper) }

    fun chooseLimit(limit: ScanSizeLimit) = updateSession { it.copy(limit = limit) }

    fun typeCustomKb(text: String) = updateSession { it.copy(customKb = text.filter(Char::isDigit).take(TargetInput.MAX_DIGITS)) }

    // Making the PDF

    fun create() {
        val state = session.value
        if (!state.canCreate || state.editingId != null) return
        val maxBytes = state.maxBytes()
        runWork {
            val workspace = workspace()
            val stamp = System.currentTimeMillis()
            val folder = File(workspace, "build-$stamp")
            val output = File(workspace, "scan-$stamp.pdf")
            val previous = session.value.result?.path
            try {
                when (val outcome = writer.write(state.pages, state.paper, maxBytes, folder, output, ::setProgress)) {
                    is CompressOutcome.Done -> {
                        previous?.let { path -> withContext(deps.ioDispatcher) { File(path).delete() } }
                        updateSession { it.copy(result = ScanResult(output.path, outcome.sizeBytes, outcome.pageCount, maxBytes)) }
                    }
                    is CompressOutcome.TooLarge ->
                        showProblem(PdfProblem.TooLarge(outcome.smallestBytes, maxBytes ?: outcome.smallestBytes))
                }
            } finally {
                withContext(NonCancellable + deps.ioDispatcher) { folder.deleteRecursively() }
            }
        }
    }

    fun save() {
        val result = session.value.result ?: return
        if (result.saved != null) return
        runSave {
            val exported = deps.exports.saveDocument(
                File(result.path),
                scanFileName(result.pageCount),
                PDF_MIME,
                size = null,
                maxBytes = result.maxBytes,
            )
            updateSession { it.copy(result = it.result?.copy(saved = SavedFile(exported.uri.toString(), exported.displayName))) }
            send(PdfEvent.Saved(exported.displayName, count = 1, inDocuments = true))
        }
    }

    fun share() {
        val result = session.value.result ?: return
        shareOutputs(listOf(File(result.path) to scanFileName(result.pageCount)), listOfNotNull(result.saved), PDF_MIME)
    }

    fun backToPages() = updateSession { it.copy(result = null) }

    // Previews

    private fun refreshPreview(id: String) {
        stalePreviews += id
        if (previewJob?.isActive == true) return
        previewJob = viewModelScope.launch {
            while (stalePreviews.isNotEmpty()) {
                val next = stalePreviews.first()
                stalePreviews.remove(next)
                renderPreview(next)
            }
        }
    }

    private suspend fun renderPreview(id: String) {
        val page = session.value.pages.firstOrNull { it.id == id } ?: return
        try {
            val file = File(workspace(), "preview-$id-${System.nanoTime()}.jpg")
            val bitmap = processor.render(File(page.photoPath), page.corners, page.filter, PREVIEW_LONG_EDGE)
            val bytes = try {
                codec.encode(bitmap, OutputFormat.Jpeg, PREVIEW_QUALITY)
            } finally {
                bitmap.recycle()
            }
            withContext(deps.ioDispatcher) { file.writeBytes(bytes) }
            var replaced: String? = null
            updateSession { state ->
                state.copy(
                    pages = state.pages.map { existing ->
                        if (existing.id != id) {
                            existing
                        } else {
                            replaced = existing.previewPath
                            existing.copy(previewPath = file.path)
                        }
                    },
                )
            }
            replaced?.let { old -> withContext(deps.ioDispatcher) { File(old).delete() } }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The list falls back to showing the photo itself.
        }
    }

    private companion object {
        const val PREVIEW_LONG_EDGE = 900
        const val PREVIEW_QUALITY = 85
    }
}
