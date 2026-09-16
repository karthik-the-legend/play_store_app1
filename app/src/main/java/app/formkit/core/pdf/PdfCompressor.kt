package app.formkit.core.pdf

import android.graphics.Bitmap
import app.formkit.core.di.DefaultDispatcher
import app.formkit.core.imaging.AndroidImageCodec
import app.formkit.core.imaging.OutputFormat
import app.formkit.core.imaging.PixelSize
import app.formkit.core.imaging.SizeTarget
import app.formkit.core.imaging.SizeTargeter
import app.formkit.core.imaging.TargetOutcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.math.max

sealed interface CompressOutcome {
    data class Done(val sizeBytes: Long, val pageCount: Int) : CompressOutcome

    /** Even the smallest pages can't fit. [smallestBytes] is roughly the smallest PDF possible. */
    data class TooLarge(val smallestBytes: Long) : CompressOutcome
}

data class CompressProgress(val stage: Stage, val page: Int, val pageCount: Int) {
    enum class Stage { Analysing, Compressing, Writing }
}

/** Pages to fit under a byte limit: where each goes, and a way to draw it at up to a given size. */
interface PageImages {
    val pageCount: Int

    fun placement(index: Int): PagePlacement

    /** Page [index] as a new bitmap, no more than [maxLongEdge] pixels on its long side. */
    fun render(index: Int, maxLongEdge: Int): Bitmap
}

/**
 * Makes a PDF of page images no bigger than a byte limit, using §4.1's search on every page. Compress
 * PDF feeds it the pages of an existing PDF (so text stops being selectable, which the tool warns
 * about); Scan to PDF feeds it straightened photos.
 *
 * 1. A quick pass renders a small preview of every page. Its JPEG size is the page's weight (busy
 *    pages get more bytes), and a tiny low-quality encode estimates the page's floor.
 * 2. If the floors and the PDF's own overhead are already over the limit, it stops right there.
 * 3. Otherwise each page is rendered and fitted to its share of the budget. A page that can't fit
 *    raises its floor and the budget is re-split.
 * 4. The rebuilt PDF is measured on disk. If it's over, the budget shrinks by the overshoot and
 *    the pages are redone.
 */
class PdfCompressor @Inject constructor(
    private val documents: PdfDocuments,
    private val codec: AndroidImageCodec,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {

    suspend fun compress(
        source: File,
        maxBytes: Long,
        workDir: File,
        output: File,
        onProgress: (CompressProgress) -> Unit = {},
    ): CompressOutcome = withContext(dispatcher) {
        PdfRasterizer(source).use { raster -> fit(RasterPages(raster), maxBytes, workDir, output, onProgress) }
    }

    suspend fun fit(
        pages: PageImages,
        maxBytes: Long,
        workDir: File,
        output: File,
        onProgress: (CompressProgress) -> Unit = {},
    ): CompressOutcome = withContext(dispatcher) {
        workDir.mkdirs()
        val pageCount = pages.pageCount
        require(pageCount > 0) { "The PDF has no pages" }

        val placements = ArrayList<PagePlacement>(pageCount)
        val weights = ArrayList<Long>(pageCount)
        val floors = ArrayList<Long>(pageCount)
        for (index in 0 until pageCount) {
            ensureActive()
            onProgress(CompressProgress(CompressProgress.Stage.Analysing, index + 1, pageCount))
            placements += pages.placement(index)
            val preview = pages.render(index, PREVIEW_LONG_EDGE)
            try {
                weights += codec.encode(preview, OutputFormat.Jpeg, PREVIEW_QUALITY).size.toLong()
                floors += floorBytes(preview)
            } finally {
                preview.recycle()
            }
        }
        if (ByteBudget.smallestTotal(floors) > maxBytes) {
            return@withContext CompressOutcome.TooLarge(ByteBudget.smallestTotal(floors))
        }

        var scale = 1.0
        repeat(MAX_ATTEMPTS) {
            val budgets = ByteBudget.split(maxBytes, weights, floors, scale)
                ?: return@withContext CompressOutcome.TooLarge(ByteBudget.smallestTotal(floors))

            val written = ArrayList<ImagePage>(pageCount)
            var pageMissed = false
            for (index in 0 until pageCount) {
                ensureActive()
                onProgress(CompressProgress(CompressProgress.Stage.Compressing, index + 1, pageCount))
                val rendered = pages.render(index, MAX_RENDER_EDGE)
                val outcome = try {
                    SizeTargeter(codec).fit(rendered, SizeTarget(maxBytes = budgets[index], format = OutputFormat.Jpeg, allowDownscale = true))
                } finally {
                    rendered.recycle()
                }
                when (outcome) {
                    is TargetOutcome.Fitted -> {
                        val jpeg = File(workDir, "page-$index.jpg").apply { writeBytes(outcome.bytes) }
                        val placement = placements[index]
                        written += ImagePage(jpeg, placement.pageWidth, placement.pageHeight, placement.image)
                    }
                    is TargetOutcome.TooLarge -> {
                        floors[index] = max(floors[index], outcome.smallestBytes)
                        pageMissed = true
                    }
                    is TargetOutcome.TooSmall -> error("No minimum size was requested")
                }
            }
            if (pageMissed) return@repeat

            ensureActive()
            onProgress(CompressProgress(CompressProgress.Stage.Writing, pageCount, pageCount))
            documents.writeImagePages(written, output)
            val size = output.length()
            if (size <= maxBytes) return@withContext CompressOutcome.Done(size, pageCount)
            val overhead = ByteBudget.overhead(pageCount)
            scale = (scale * (maxBytes - overhead).toDouble() / max(1L, size - overhead) * SHRINK_MARGIN).coerceIn(MIN_SCALE, 1.0)
        }
        output.delete()
        CompressOutcome.TooLarge(ByteBudget.smallestTotal(floors))
    }

    /** Roughly the smallest JPEG this page can be: the size search's 100 px floor at quality 1. */
    private fun floorBytes(preview: Bitmap): Long {
        val size = PixelSize(preview.width, preview.height)
        val floorSize = if (size.shortEdge <= SizeTargeter.MIN_SHORT_EDGE) {
            size
        } else {
            size.scaledBy(SizeTargeter.MIN_SHORT_EDGE.toDouble() / size.shortEdge)
        }
        val small = if (floorSize == size) preview else codec.scale(preview, floorSize)
        try {
            return codec.encode(small, OutputFormat.Jpeg, SizeTargeter.MIN_QUALITY).size.toLong()
        } finally {
            if (small !== preview) small.recycle()
        }
    }

    /** An existing PDF's pages, each image filling its original page. */
    private class RasterPages(private val raster: PdfRasterizer) : PageImages {
        override val pageCount: Int get() = raster.pageCount

        override fun placement(index: Int): PagePlacement {
            val (width, height) = raster.pageSize(index)
            return PagePlacement(width, height, PointRect(0f, 0f, width, height))
        }

        override fun render(index: Int, maxLongEdge: Int): Bitmap = raster.render(index, RENDER_DPI, maxLongEdge)
    }

    companion object {
        /** The largest a page is ever drawn for fitting: A4 at about 200 DPI. */
        const val MAX_RENDER_EDGE = 2400
        private const val RENDER_DPI = 200f
        private const val PREVIEW_LONG_EDGE = 640
        private const val PREVIEW_QUALITY = 60
        private const val MAX_ATTEMPTS = 4
        private const val SHRINK_MARGIN = 0.97
        private const val MIN_SCALE = 0.05
    }
}
