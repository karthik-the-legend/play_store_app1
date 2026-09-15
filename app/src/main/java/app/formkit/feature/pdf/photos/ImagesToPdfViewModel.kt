package app.formkit.feature.pdf.photos

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import app.formkit.core.imaging.AndroidImageCodec
import app.formkit.core.imaging.OutputFormat
import app.formkit.core.imaging.PixelSize
import app.formkit.core.imaging.SourceImageDecoder
import app.formkit.core.imaging.flattenOntoWhite
import app.formkit.core.pdf.ImagePage
import app.formkit.core.pdf.PageLayout
import app.formkit.core.pdf.PageMargin
import app.formkit.core.pdf.PageOrientation
import app.formkit.core.pdf.PaperSize
import app.formkit.feature.pdf.common.PDF_MIME
import app.formkit.feature.pdf.common.PdfEvent
import app.formkit.feature.pdf.common.PdfToolDeps
import app.formkit.feature.pdf.common.PdfToolViewModel
import app.formkit.feature.pdf.common.PickedImage
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
import kotlin.math.max

@Serializable
data class ImagesPdfResult(val path: String, val sizeBytes: Long, val pageCount: Int, val saved: SavedFile? = null)

@Serializable
data class ImagesToPdfSession(
    val images: List<PickedImage> = emptyList(),
    val paper: PaperSize = PaperSize.A4,
    val orientation: PageOrientation = PageOrientation.Auto,
    val margin: PageMargin = PageMargin.Small,
    val result: ImagesPdfResult? = null,
) {
    val canAddMore: Boolean get() = images.size < MAX_IMAGES

    companion object {
        const val MAX_IMAGES = 50
    }
}

/** "Photos_3_pages.pdf". Picked photos rarely have meaningful names to borrow. */
fun imagesPdfFileName(pageCount: Int): String = "Photos_${pageCount}_pages.pdf"

@HiltViewModel
class ImagesToPdfViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    deps: PdfToolDeps,
    private val decoder: SourceImageDecoder,
    private val codec: AndroidImageCodec,
) : PdfToolViewModel<ImagesToPdfSession>(savedStateHandle, ImagesToPdfSession.serializer(), ImagesToPdfSession(), deps) {

    override val workspacePrefix = "images-pdf"

    override fun pdfsIn(state: ImagesToPdfSession): List<PickedPdf> = emptyList()

    override fun replacePdf(state: ImagesToPdfSession, pdf: PickedPdf): ImagesToPdfSession = state

    fun addImages(uris: List<Uri>) {
        val room = max(0, ImagesToPdfSession.MAX_IMAGES - session.value.images.size)
        importImages(uris.take(room)) { state, imported -> state.copy(images = state.images + imported, result = null) }
    }

    fun remove(id: String) = updateSession { state -> state.copy(images = state.images.filterNot { it.id == id }) }

    fun move(from: Int, to: Int) = updateSession { state ->
        if (from !in state.images.indices || to !in state.images.indices) return@updateSession state
        state.copy(images = state.images.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun choosePaper(paper: PaperSize) = updateSession { it.copy(paper = paper) }

    fun chooseOrientation(orientation: PageOrientation) = updateSession { it.copy(orientation = orientation) }

    fun chooseMargin(margin: PageMargin) = updateSession { it.copy(margin = margin) }

    fun create() {
        val state = session.value
        if (state.images.isEmpty()) return
        runWork {
            val workspace = workspace()
            val stamp = System.currentTimeMillis()
            val folder = File(workspace, "pages-$stamp")
            val output = File(workspace, "photos-$stamp.pdf")
            val previous = session.value.result?.path
            try {
                val pages = withContext(deps.defaultDispatcher) {
                    folder.mkdirs()
                    state.images.mapIndexed { index, image ->
                        currentCoroutineContext().ensureActive()
                        setProgress(WorkProgress(ProgressStage.Photos, index + 1, state.images.size))
                        pageFor(image, File(folder, "$index.jpg"), state)
                    }
                }
                setProgress(WorkProgress(ProgressStage.Writing, pages.size, pages.size))
                deps.documents.writeImagePages(pages, output)
                val sizeBytes = withContext(deps.ioDispatcher) {
                    previous?.let { File(it).delete() }
                    output.length()
                }
                updateSession { it.copy(result = ImagesPdfResult(output.path, sizeBytes, pages.size)) }
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + deps.ioDispatcher) { folder.deleteRecursively() }
            }
        }
    }

    /** Decodes a photo, limits it to A4 at 300 DPI, puts transparency on white, and writes it as a JPEG. */
    private fun pageFor(image: PickedImage, jpeg: File, state: ImagesToPdfSession): ImagePage {
        val decoded = decoder.decode(File(image.path), maxLongEdge = MAX_IMAGE_EDGE * 2).bitmap
        val sized = fitWithin(decoded)
        val flat = if (sized.hasAlpha()) flattenOntoWhite(sized).also { sized.recycle() } else sized
        try {
            val size = PixelSize(flat.width, flat.height)
            jpeg.writeBytes(codec.encode(flat, OutputFormat.Jpeg, JPEG_QUALITY))
            val placement = PageLayout.place(size, state.paper, state.orientation, state.margin)
            return ImagePage(jpeg, placement.pageWidth, placement.pageHeight, placement.image)
        } finally {
            flat.recycle()
        }
    }

    private fun fitWithin(bitmap: Bitmap): Bitmap {
        val size = PixelSize(bitmap.width, bitmap.height)
        if (size.longEdge <= MAX_IMAGE_EDGE) return bitmap
        val scaled = codec.scale(bitmap, size.scaledBy(MAX_IMAGE_EDGE.toDouble() / size.longEdge))
        bitmap.recycle()
        return scaled
    }

    fun save() {
        val result = session.value.result ?: return
        if (result.saved != null) return
        runSave {
            val exported = deps.exports.saveDocument(File(result.path), imagesPdfFileName(result.pageCount), PDF_MIME, size = null)
            updateSession { it.copy(result = it.result?.copy(saved = SavedFile(exported.uri.toString(), exported.displayName))) }
            send(PdfEvent.Saved(exported.displayName, count = 1, inDocuments = true))
        }
    }

    fun share() {
        val result = session.value.result ?: return
        shareOutputs(listOf(File(result.path) to imagesPdfFileName(result.pageCount)), listOfNotNull(result.saved), PDF_MIME)
    }

    fun backToList() = updateSession { it.copy(result = null) }

    private companion object {
        /** A4's long side at 300 DPI: sharp in print, and a photo from a 50 MP camera stays manageable. */
        const val MAX_IMAGE_EDGE = 3508
        const val JPEG_QUALITY = 88
    }
}
