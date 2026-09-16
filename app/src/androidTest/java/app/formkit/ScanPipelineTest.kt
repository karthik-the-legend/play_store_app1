package app.formkit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.formkit.core.imaging.AndroidImageCodec
import app.formkit.core.imaging.PixelSize
import app.formkit.core.imaging.SourceImageDecoder
import app.formkit.core.imaging.scan.PageCorners
import app.formkit.core.imaging.scan.ScanFilter
import app.formkit.core.pdf.CompressOutcome
import app.formkit.core.pdf.PaperSize
import app.formkit.core.pdf.PdfCompressor
import app.formkit.core.pdf.PdfDocuments
import app.formkit.core.pdf.PdfRasterizer
import app.formkit.feature.scan.ScanPage
import app.formkit.feature.scan.ScanPageProcessor
import app.formkit.feature.scan.ScanPdfWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/** Scanning on a real device: finding the page, straightening it, and writing the PDF. */
@RunWith(AndroidJUnit4::class)
class ScanPipelineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val decoder = SourceImageDecoder()
    private val codec = AndroidImageCodec()
    private val processor = ScanPageProcessor(decoder, codec, Dispatchers.Default)
    private val documents = PdfDocuments(context, Dispatchers.IO)
    private val compressor = PdfCompressor(documents, codec, Dispatchers.Default)
    private val writer = ScanPdfWriter(processor, decoder, codec, compressor, documents, Dispatchers.IO)
    private lateinit var dir: File

    /** A page lying at an angle, seen from slightly off to one side. */
    private val tilted = floatArrayOf(250f, 90f, 980f, 150f, 930f, 800f, 190f, 740f)

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "scan-pipeline-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun theOutlineOfAPageIsFound() = runBlocking {
        val photo = photoOfPage("tilted.jpg", tilted)

        val found = processor.findPage(photo)

        assertNotNull("no page found on the photo", found)
        val corners = found!!.corners.toPixels(PixelSize(PHOTO_WIDTH, PHOTO_HEIGHT))
        for (i in 0 until 4) {
            val distance = hypot(corners[2 * i] - tilted[2 * i], corners[2 * i + 1] - tilted[2 * i + 1])
            assertTrue("corner $i is $distance px away from where the page was drawn", distance <= 24f)
        }
    }

    @Test
    fun theStraightenedPageIsFlatAndWhite() = runBlocking {
        val photo = photoOfPage("tilted.jpg", tilted)
        val corners = processor.findPage(photo)!!.corners

        val page = processor.render(photo, corners, ScanFilter.Enhanced, maxLongEdge = 1200)

        try {
            // Perspective can't be undone exactly without knowing the lens, so the straightened
            // page takes the longer of each pair of opposite sides, as drawn on the photo.
            val expectedWidth = maxOf(sideLength(0, 1), sideLength(3, 2))
            val expectedHeight = maxOf(sideLength(0, 3), sideLength(1, 2))
            assertTrue(
                "straightened page is ${page.width}x${page.height}, expected about ${expectedWidth.toInt()}x${expectedHeight.toInt()}",
                abs(page.width - expectedWidth) < expectedWidth * 0.03f && abs(page.height - expectedHeight) < expectedHeight * 0.03f,
            )
            for ((x, y) in corners(page)) {
                val corner = page.getPixel(x, y)
                assertTrue("the table is still in the corner at $x,$y", Color.red(corner) > 200 && Color.blue(corner) > 200)
            }
            assertTrue("no printed lines survived", darkPixels(page) > page.width * page.height / 100)
        } finally {
            page.recycle()
        }
    }

    @Test
    fun aScanFitsUnderAKilobyteLimit() = runBlocking {
        val pages = listOf(scanPage("one.jpg"), scanPage("two.jpg"))
        val output = File(dir, "limited.pdf")

        val outcome = writer.write(pages, PaperSize.A4, maxBytes = 150_000, workDir = File(dir, "work"), output = output)

        assertTrue("didn't fit: $outcome", outcome is CompressOutcome.Done)
        assertTrue("the PDF is ${output.length()} bytes", output.length() <= 150_000)
        assertEquals(2, PdfRasterizer(output).use { it.pageCount })
    }

    @Test
    fun withoutALimitEveryPageIsWritten() = runBlocking {
        val pages = listOf(scanPage("one.jpg"), scanPage("two.jpg"), scanPage("three.jpg"))
        val output = File(dir, "unlimited.pdf")

        val outcome = writer.write(pages, PaperSize.FitToImage, maxBytes = null, workDir = File(dir, "work"), output = output)

        assertEquals(3, (outcome as CompressOutcome.Done).pageCount)
        assertEquals(3, PdfRasterizer(output).use { it.pageCount })
    }

    /** The distance between two of the drawn page's corners on the photo. */
    private fun sideLength(from: Int, to: Int) =
        hypot(tilted[2 * to] - tilted[2 * from], tilted[2 * to + 1] - tilted[2 * from + 1])

    private suspend fun scanPage(name: String): ScanPage {
        val photo = photoOfPage(name, tilted)
        val found = processor.findPage(photo)
        return ScanPage(
            id = name,
            photoPath = photo.path,
            photoWidth = PHOTO_WIDTH,
            photoHeight = PHOTO_HEIGHT,
            corners = found?.corners ?: PageCorners.WholePhoto,
            foundCorners = found?.corners,
            filter = ScanFilter.Enhanced,
        )
    }

    /** Twenty pixels in from each corner of the straightened page. */
    private fun corners(page: Bitmap) = listOf(
        20 to 20,
        page.width - 21 to 20,
        page.width - 21 to page.height - 21,
        20 to page.height - 21,
    )

    private fun darkPixels(page: Bitmap): Int {
        var dark = 0
        for (y in 0 until page.height step 2) {
            for (x in 0 until page.width step 2) if (Color.red(page.getPixel(x, y)) < 100) dark += 4
        }
        return dark
    }

    /** A photo of a printed page on a dark table, lit unevenly, saved as a JPEG. */
    private fun photoOfPage(name: String, corners: FloatArray): File {
        val page = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        Canvas(page).apply {
            drawColor(Color.WHITE)
            val ink = Paint().apply { color = Color.rgb(25, 25, 30) }
            for (row in 0 until 19) {
                val top = 60f + row * 38f
                drawRect(60f, top, PAGE_WIDTH - 60f - (row % 4) * 90f, top + 13f, ink)
            }
        }
        val photo = Bitmap.createBitmap(PHOTO_WIDTH, PHOTO_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(photo)
        canvas.drawColor(Color.rgb(58, 60, 64))
        val matrix = Matrix()
        check(
            matrix.setPolyToPoly(
                floatArrayOf(0f, 0f, PAGE_WIDTH.toFloat(), 0f, PAGE_WIDTH.toFloat(), PAGE_HEIGHT.toFloat(), 0f, PAGE_HEIGHT.toFloat()),
                0,
                corners,
                0,
                4,
            ),
        ) { "The test's corners don't make a quadrilateral" }
        canvas.drawBitmap(page, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        page.recycle()
        // Light falling off across the photo, as in a real hand-held shot.
        canvas.drawRect(
            0f,
            0f,
            PHOTO_WIDTH.toFloat(),
            PHOTO_HEIGHT.toFloat(),
            Paint().apply {
                shader = LinearGradient(0f, 0f, PHOTO_WIDTH.toFloat(), 0f, 0x00000000, 0x66000000, Shader.TileMode.CLAMP)
            },
        )
        val file = File(dir, name)
        file.outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        photo.recycle()
        return file
    }

    private companion object {
        const val PHOTO_WIDTH = 1200
        const val PHOTO_HEIGHT = 900
        const val PAGE_WIDTH = 600
        const val PAGE_HEIGHT = 800
    }
}
