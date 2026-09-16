package app.formkit.feature.scan

import android.graphics.Bitmap
import app.formkit.core.di.IoDispatcher
import app.formkit.core.imaging.AndroidImageCodec
import app.formkit.core.imaging.OutputFormat
import app.formkit.core.imaging.PixelSize
import app.formkit.core.imaging.SourceImageDecoder
import app.formkit.core.pdf.CompressOutcome
import app.formkit.core.pdf.CompressProgress
import app.formkit.core.pdf.ImagePage
import app.formkit.core.pdf.PageImages
import app.formkit.core.pdf.PageLayout
import app.formkit.core.pdf.PageMargin
import app.formkit.core.pdf.PageOrientation
import app.formkit.core.pdf.PagePlacement
import app.formkit.core.pdf.PaperSize
import app.formkit.core.pdf.PdfCompressor
import app.formkit.core.pdf.PdfDocuments
import app.formkit.feature.pdf.common.ProgressStage
import app.formkit.feature.pdf.common.WorkProgress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** Turns scanned pages into one PDF, under a byte limit when there is one. */
class ScanPdfWriter @Inject constructor(
    private val processor: ScanPageProcessor,
    private val decoder: SourceImageDecoder,
    private val codec: AndroidImageCodec,
    private val compressor: PdfCompressor,
    private val documents: PdfDocuments,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun write(
        pages: List<ScanPage>,
        paper: PaperSize,
        maxBytes: Long?,
        workDir: File,
        output: File,
        onProgress: (WorkProgress) -> Unit = {},
    ): CompressOutcome {
        require(pages.isNotEmpty()) { "A PDF needs at least one page" }
        withContext(ioDispatcher) { workDir.mkdirs() }

        // Every page is straightened once. With a limit, the fitting pass re-encodes these.
        val quality = if (maxBytes == null) FINAL_QUALITY else WORKING_QUALITY
        val prepared = pages.mapIndexed { index, page ->
            currentCoroutineContext().ensureActive()
            onProgress(WorkProgress(ProgressStage.Photos, index + 1, pages.size))
            val bitmap = processor.render(File(page.photoPath), page.corners, page.filter, PdfCompressor.MAX_RENDER_EDGE)
            try {
                val bytes = codec.encode(bitmap, OutputFormat.Jpeg, quality)
                val file = File(workDir, "page-$index.jpg")
                withContext(ioDispatcher) { file.writeBytes(bytes) }
                PreparedPage(file, PixelSize(bitmap.width, bitmap.height))
            } finally {
                bitmap.recycle()
            }
        }

        if (maxBytes == null) {
            onProgress(WorkProgress(ProgressStage.Writing, pages.size, pages.size))
            documents.writeImagePages(
                prepared.map { page ->
                    val placement = scanPlacement(page.size, paper)
                    ImagePage(page.file, placement.pageWidth, placement.pageHeight, placement.image)
                },
                output,
            )
            return CompressOutcome.Done(withContext(ioDispatcher) { output.length() }, pages.size)
        }

        return compressor.fit(PreparedPages(prepared, paper), maxBytes, File(workDir, "fitted"), output) { progress ->
            val stage = when (progress.stage) {
                CompressProgress.Stage.Analysing -> ProgressStage.Analysing
                CompressProgress.Stage.Compressing -> ProgressStage.Pages
                CompressProgress.Stage.Writing -> ProgressStage.Writing
            }
            onProgress(WorkProgress(stage, progress.page, progress.pageCount))
        }
    }

    private class PreparedPage(val file: File, val size: PixelSize)

    private inner class PreparedPages(private val pages: List<PreparedPage>, private val paper: PaperSize) : PageImages {
        override val pageCount: Int get() = pages.size

        override fun placement(index: Int): PagePlacement = scanPlacement(pages[index].size, paper)

        override fun render(index: Int, maxLongEdge: Int): Bitmap {
            val decoded = decoder.decode(pages[index].file, maxLongEdge = maxLongEdge * 2).bitmap
            val size = PixelSize(decoded.width, decoded.height)
            if (size.longEdge <= maxLongEdge) return decoded
            val scaled = codec.scale(decoded, size.scaledBy(maxLongEdge.toDouble() / size.longEdge))
            decoded.recycle()
            return scaled
        }
    }

    companion object {
        /**
         * Pages shaped like the scan show it at this density: a full A4 page straightened to
         * [PdfCompressor.MAX_RENDER_EDGE] pixels comes out at A4's real size.
         */
        const val SCAN_FIT_DPI = 205f
        private const val FINAL_QUALITY = 88
        private const val WORKING_QUALITY = 95

        fun scanPlacement(size: PixelSize, paper: PaperSize): PagePlacement =
            PageLayout.place(size, paper, PageOrientation.Auto, PageMargin.None, fitDpi = SCAN_FIT_DPI)
    }
}
