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

/**
 * Shrinks a PDF to a byte limit by turning each page into a JPEG (§4.1's search, per page) and
 * rebuilding the document. Text stops being selectable, which the tool screen warns about.
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
        workDir.mkdirs()
        PdfRasterizer(source).use { raster ->
            val pageCount = raster.pageCount
            require(pageCount > 0) { "The PDF has no pages" }

            val pageSizes = ArrayList<Pair<Float, Float>>(pageCount)
            val weights = ArrayList<Long>(pageCount)
            val floors = ArrayList<Long>(pageCount)
            for (index in 0 until pageCount) {
                ensureActive()
                onProgress(CompressProgress(CompressProgress.Stage.Analysing, index + 1, pageCount))
                pageSizes += raster.pageSize(index)
                val preview = raster.render(index, RENDER_DPI, PREVIEW_LONG_EDGE)
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

                val pages = ArrayList<ImagePage>(pageCount)
                var pageMissed = false
                for (index in 0 until pageCount) {
                    ensureActive()
                    onProgress(CompressProgress(CompressProgress.Stage.Compressing, index + 1, pageCount))
                    val rendered = raster.render(index, RENDER_DPI, MAX_RENDER_EDGE)
                    val outcome = try {
                        SizeTargeter(codec).fit(rendered, SizeTarget(maxBytes = budgets[index], format = OutputFormat.Jpeg, allowDownscale = true))
                    } finally {
                        rendered.recycle()
                    }
                    when (outcome) {
                        is TargetOutcome.Fitted -> {
                            val jpeg = File(workDir, "page-$index.jpg").apply { writeBytes(outcome.bytes) }
                            val (width, height) = pageSizes[index]
                            pages += ImagePage(jpeg, width, height, PointRect(0f, 0f, width, height))
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
                documents.writeImagePages(pages, output)
                val written = output.length()
                if (written <= maxBytes) return@withContext CompressOutcome.Done(written, pageCount)
                val overhead = ByteBudget.overhead(pageCount)
                scale = (scale * (maxBytes - overhead).toDouble() / max(1L, written - overhead) * SHRINK_MARGIN).coerceIn(MIN_SCALE, 1.0)
            }
            output.delete()
            CompressOutcome.TooLarge(ByteBudget.smallestTotal(floors))
        }
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

    private companion object {
        const val RENDER_DPI = 200f
        const val MAX_RENDER_EDGE = 2400
        const val PREVIEW_LONG_EDGE = 640
        const val PREVIEW_QUALITY = 60
        const val MAX_ATTEMPTS = 4
        const val SHRINK_MARGIN = 0.97
        const val MIN_SCALE = 0.05
    }
}
