package app.formkit.feature.pdf.images

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import app.formkit.core.imaging.AndroidImageCodec
import app.formkit.core.imaging.OutputFormat
import app.formkit.core.imaging.PixelSize
import app.formkit.core.pdf.PageRanges
import app.formkit.core.pdf.PdfRasterizer
import app.formkit.core.pdf.RangeParse
import app.formkit.feature.pdf.common.PdfEvent
import app.formkit.feature.pdf.common.PdfNames
import app.formkit.feature.pdf.common.PdfToolDeps
import app.formkit.feature.pdf.common.PdfToolViewModel
import app.formkit.feature.pdf.common.PickedPdf
import app.formkit.feature.pdf.common.ProgressStage
import app.formkit.feature.pdf.common.SavedFile
import app.formkit.feature.pdf.common.WorkProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import javax.inject.Inject

enum class ImageDpi(val dpi: Int) {
    Screen(72),
    Standard(150),
    Print(300),
}

enum class PageSelection { All, Some }

@Serializable
data class RenderedPage(
    val path: String,
    val name: String,
    val pageNumber: Int,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
)

@Serializable
data class PdfToImagesSession(
    val pdf: PickedPdf? = null,
    val dpi: ImageDpi = ImageDpi.Standard,
    val format: OutputFormat = OutputFormat.Jpeg,
    val selection: PageSelection = PageSelection.All,
    val rangeText: String = "",
    val pages: List<RenderedPage> = emptyList(),
    val saved: List<SavedFile> = emptyList(),
    /** The DPI and format the finished [pages] were made with, which the options may no longer match. */
    val madeWithDpi: Int? = null,
    val madeWithFormat: OutputFormat? = null,
) {
    fun parsedRanges(): RangeParse? = pdf?.takeIf { it.isReady }?.let { PageRanges.parse(rangeText, it.pageCount) }

    /** Zero-based pages to export, or null while the typed range isn't usable. */
    fun pageIndices(): List<Int>? {
        val ready = pdf?.takeIf { it.isReady } ?: return null
        return when (selection) {
            PageSelection.All -> (0 until ready.pageCount).toList()
            PageSelection.Some -> (parsedRanges() as? RangeParse.Valid)?.let { PageRanges.pageIndices(it.ranges) }
        }
    }

    /** The folder under Pictures/FormKit the images are saved into. */
    val folderName: String get() = PdfNames.baseName(pdf?.displayName.orEmpty())
}

@HiltViewModel
class PdfToImagesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    deps: PdfToolDeps,
    private val codec: AndroidImageCodec,
) : PdfToolViewModel<PdfToImagesSession>(savedStateHandle, PdfToImagesSession.serializer(), PdfToImagesSession(), deps) {

    override val workspacePrefix = "pdf-images"

    override fun pdfsIn(state: PdfToImagesSession): List<PickedPdf> = listOfNotNull(state.pdf)

    override fun replacePdf(state: PdfToImagesSession, pdf: PickedPdf): PdfToImagesSession =
        if (state.pdf?.id == pdf.id) state.copy(pdf = pdf) else state

    fun pickPdf(uri: Uri) = importPdfs(listOf(uri)) { state, imported ->
        state.copy(pdf = imported.first(), rangeText = "", pages = emptyList(), saved = emptyList(), madeWithDpi = null, madeWithFormat = null)
    }

    fun chooseDpi(dpi: ImageDpi) = updateSession { it.copy(dpi = dpi) }

    fun chooseFormat(format: OutputFormat) = updateSession { it.copy(format = format) }

    fun chooseSelection(selection: PageSelection) = updateSession { it.copy(selection = selection) }

    fun typeRanges(text: String) = updateSession { it.copy(rangeText = text.take(MAX_RANGE_TEXT)) }

    fun export() {
        val state = session.value
        val pdf = state.pdf?.takeIf { it.isReady } ?: return
        val indices = state.pageIndices()?.takeIf { it.isNotEmpty() } ?: return
        val source = File(checkNotNull(pdf.readablePath))
        val base = PdfNames.baseName(pdf.displayName)
        val format = state.format
        val dpi = state.dpi.dpi
        // "Statement_page_07.jpg" sorts correctly in a gallery when there are 10 or more pages.
        val digits = pdf.pageCount.toString().length
        runWork {
            val folder = File(workspace(), "images-${System.currentTimeMillis()}")
            val previous = session.value.pages.map { it.path }
            val pages = withContext(deps.defaultDispatcher) {
                folder.mkdirs()
                PdfRasterizer(source).use { raster ->
                    indices.mapIndexed { position, index ->
                        currentCoroutineContext().ensureActive()
                        setProgress(WorkProgress(ProgressStage.Pages, position + 1, indices.size))
                        val bitmap = raster.render(index, dpi.toFloat(), MAX_LONG_EDGE)
                        val size = PixelSize(bitmap.width, bitmap.height)
                        val bytes = try {
                            codec.encode(bitmap, format, JPEG_QUALITY)
                        } finally {
                            bitmap.recycle()
                        }
                        val pageNumber = index + 1
                        val name = "${base}_page_${pageNumber.toString().padStart(digits, '0')}.${format.extension}"
                        val file = File(folder, name).apply { writeBytes(bytes) }
                        RenderedPage(file.path, name, pageNumber, size.width, size.height, bytes.size.toLong())
                    }
                }
            }
            withContext(deps.ioDispatcher) { previous.forEach { File(it).delete() } }
            updateSession { it.copy(pages = pages, saved = emptyList(), madeWithDpi = dpi, madeWithFormat = format) }
        }
    }

    fun saveAll() {
        val state = session.value
        if (state.pages.isEmpty() || state.saved.size == state.pages.size) return
        val format = state.madeWithFormat ?: state.format
        val folder = state.folderName
        runSave {
            val saved = state.pages.map { page ->
                val exported = deps.exports.save(
                    file = File(page.path),
                    displayName = page.name,
                    format = format,
                    maxBytes = null,
                    size = PixelSize(page.width, page.height),
                    subfolder = folder,
                )
                SavedFile(exported.uri.toString(), exported.displayName)
            }
            updateSession { it.copy(saved = saved) }
            send(PdfEvent.Saved(saved.first().displayName, count = saved.size, inDocuments = false, folder = "Pictures/FormKit/$folder"))
        }
    }

    fun shareAll() {
        val state = session.value
        val format = state.madeWithFormat ?: state.format
        shareOutputs(state.pages.map { File(it.path) to it.name }, state.saved, format.mimeType)
    }

    fun backToOptions() = updateSession { it.copy(pages = emptyList(), saved = emptyList(), madeWithDpi = null, madeWithFormat = null) }

    private companion object {
        /** A4 at 300 DPI is 3508 px tall; this leaves a little room without huge bitmaps. */
        const val MAX_LONG_EDGE = 4000
        const val JPEG_QUALITY = 92
        const val MAX_RANGE_TEXT = 200
    }
}
